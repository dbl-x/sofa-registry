/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.registry.server.session.registry;

import com.alipay.sofa.registry.common.model.ConnectId;
import com.alipay.sofa.registry.common.model.dataserver.DatumVersion;
import com.alipay.sofa.registry.common.model.store.*;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.remoting.Channel;
import com.alipay.sofa.registry.remoting.Server;
import com.alipay.sofa.registry.remoting.exchange.Exchange;
import com.alipay.sofa.registry.server.session.acceptor.ClientOffWriteDataRequest;
import com.alipay.sofa.registry.server.session.acceptor.PublisherRegisterWriteDataRequest;
import com.alipay.sofa.registry.server.session.acceptor.PublisherUnregisterWriteDataRequest;
import com.alipay.sofa.registry.server.session.acceptor.WriteDataAcceptor;
import com.alipay.sofa.registry.server.session.bootstrap.ExecutorManager;
import com.alipay.sofa.registry.server.session.bootstrap.SessionServerConfig;
import com.alipay.sofa.registry.server.session.interceptor.OrderedInterceptorManager;
import com.alipay.sofa.registry.server.session.interceptor.RegisterInvokeData;
import com.alipay.sofa.registry.server.session.loggers.Loggers;
import com.alipay.sofa.registry.server.session.metadata.MetadataCacheRegistry;
import com.alipay.sofa.registry.server.session.providedata.ConfigProvideDataWatcher;
import com.alipay.sofa.registry.server.session.push.FirePushService;
import com.alipay.sofa.registry.server.session.push.PushSwitchService;
import com.alipay.sofa.registry.server.session.push.TriggerPushContext;
import com.alipay.sofa.registry.server.session.store.PublisherStore;
import com.alipay.sofa.registry.server.session.store.SubscriberStore;
import com.alipay.sofa.registry.server.session.store.WatcherStore;
import com.alipay.sofa.registry.server.shared.env.ServerEnv;
import com.alipay.sofa.registry.util.ConcurrentUtils;
import com.alipay.sofa.registry.util.LoopRunnable;
import com.alipay.sofa.registry.util.WakeUpLoopRunnable;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

/**
 * @author shangyu.wh
 * @version $Id: AbstractSessionRegistry.java, v 0.1 2017-11-30 18:13 shangyu.wh Exp $
 */
public class SessionRegistry implements Registry {

  protected static final Logger LOGGER = LoggerFactory.getLogger(SessionRegistry.class);

  protected static final Logger SCAN_VER_LOGGER = LoggerFactory.getLogger("SCAN-VER", "[scanSubs]");

  @Autowired protected PublisherStore publisherStore;
  @Autowired protected SubscriberStore subscriberStore;
  @Autowired protected WatcherStore watcherStore;

  @Autowired protected SessionServerConfig sessionServerConfig;

  @Autowired protected PushSwitchService pushSwitchService;

  @Autowired protected Exchange boltExchange;

  @Autowired protected OrderedInterceptorManager orderedInterceptorManager;

  @Autowired protected WriteDataAcceptor writeDataAcceptor;

  @Autowired protected FirePushService firePushService;

  @Autowired protected ConfigProvideDataWatcher configProvideDataWatcher;

  @Autowired protected ClientRegistrationHook clientRegistrationHook;
  @Autowired private RegistryScanCallable registryScanCallable;

  @Autowired private MetadataCacheRegistry metadataCacheRegistry;

  @Autowired private ExecutorManager executorManager;

  private final VersionWatchDog versionWatchDog = new VersionWatchDog();

  @PostConstruct
  public void init() {
    ConcurrentUtils.createDaemonThread("SessionVerWatchDog", versionWatchDog).start();
    ConcurrentUtils.createDaemonThread("SessionClientWatchDog", new ClientWatchDog()).start();
  }

  @Override
  public void register(StoreData storeData, Channel channel) {
    RegisterInvokeData registerInvokeData = new RegisterInvokeData(storeData, channel);

    boolean allInterceptorsSuccess;
    try {
      allInterceptorsSuccess = orderedInterceptorManager.executeInterceptors(registerInvokeData);
    } catch (Exception e) {
      LOGGER.error(
          "interceptors process data(dataId={}) encountered an unexpected exception",
          registerInvokeData.getStoreData().getId(),
          e);
      throw new RuntimeException("Proceed register error!", e);
    }

    if (allInterceptorsSuccess) {
      try {
        switch (storeData.getDataType()) {
          case PUBLISHER:
            Publisher publisher = (Publisher) storeData;
            publisher.setSessionProcessId(ServerEnv.PROCESS_ID);
            if (!publisherStore.add(publisher)) {
              break;
            }
            // All write operations to DataServer (pub/unPub/clientoff/renew/snapshot)
            // are handed over to WriteDataAcceptor
            writeDataAcceptor.accept(new PublisherRegisterWriteDataRequest(publisher));
            clientRegistrationHook.afterClientRegister(storeData);
            break;
          case SUBSCRIBER:
            Subscriber subscriber = (Subscriber) storeData;
            if (!subscriberStore.add(subscriber)) {
              break;
            }
            clientRegistrationHook.afterClientRegister(storeData);
            break;
          case WATCHER:
            Watcher watcher = (Watcher) storeData;
            if (!watcherStore.add(watcher)) {
              break;
            }
            clientRegistrationHook.afterClientRegister(storeData);
            break;
          default:
            break;
        }
      } catch (Exception e) {
        throw new RuntimeException("Proceed register error!", e);
      }
    }
  }

  @Override
  public void unRegister(StoreData<String> storeData) {

    switch (storeData.getDataType()) {
      case PUBLISHER:
        Publisher publisher = (Publisher) storeData;
        publisher.setSessionProcessId(ServerEnv.PROCESS_ID);
        // no need to check whether the pub exist, make sure the unpub send to data
        publisherStore.delete(publisher.getDataInfoId(), storeData.getId());
        // All write operations to DataServer (pub/unPub/clientoff)
        // are handed over to WriteDataAcceptor
        writeDataAcceptor.accept(new PublisherUnregisterWriteDataRequest(publisher));

        clientRegistrationHook.afterClientUnregister(storeData);
        break;
      case SUBSCRIBER:
        Subscriber subscriber = (Subscriber) storeData;
        if (subscriberStore.delete(subscriber.getDataInfoId(), storeData.getId()) == null) {
          break;
        }
        clientRegistrationHook.afterClientUnregister(storeData);
        break;
      case WATCHER:
        Watcher watcher = (Watcher) storeData;
        if (watcherStore.delete(watcher.getDataInfoId(), watcher.getId()) == null) {
          break;
        }
        clientRegistrationHook.afterClientUnregister(storeData);
        break;
      default:
        break;
    }
  }

  @Override
  public void clean(List<ConnectId> connectIds) {
    disableConnect(connectIds, true, false);
  }

  @Override
  public void clientOff(List<ConnectId> connectIds) {
    ClientManagerMetric.CLIENT_OFF_COUNTER.inc(connectIds.size());
    disableConnect(connectIds, false, true);
  }

  @Override
  public void blacklist(List<ConnectId> connectIds) {
    disableConnect(connectIds, true, true);
  }

  private void disableConnect(
      List<ConnectId> connectIds, boolean removeSubAndWat, boolean checkSub) {
    Set<ConnectId> connectIdSet = Collections.unmodifiableSet(Sets.newHashSet(connectIds));
    disableConnect(connectIdSet, removeSubAndWat, checkSub, Collections.emptyMap());
  }

  private void disableConnect(
      Set<ConnectId> connectIdSet,
      boolean removeSubAndWat,
      boolean checkSub,
      Map<ConnectId, Long> connectIdVersions) {
    if (CollectionUtils.isEmpty(connectIdSet)) {
      return;
    }
    Loggers.CLIENT_DISABLE_LOG.info(
        "disable connectId={}, removeSubAndWat={}, checkSub={}, {}",
        connectIdSet.size(),
        removeSubAndWat,
        checkSub,
        connectIdSet);

    final String dataCenter = getDataCenterWhenPushEmpty();

    if (checkSub) {
      for (ConnectId connectId : connectIdSet) {
        Collection<Subscriber> subscribers = subscriberStore.getByConnectId(connectId);
        if (subscribers == null || subscribers.size() <= 0) {
          continue;
        }

        int subEmptyCount = 0;
        for (Subscriber sub : subscribers) {
          if (isPushEmpty(sub)) {
            Long clientOffVersion = connectIdVersions.get(connectId);
            if (clientOffVersion != null && clientOffVersion < sub.getRegisterTimestamp()) {
              Loggers.CLIENT_DISABLE_LOG.error(
                  "[ClientOffVersionError]subEmpty,{},{},{}, clientOffVersion={} is smaller than subRegisterTimestamp={}",
                  sub.getDataInfoId(),
                  dataCenter,
                  connectId,
                  clientOffVersion,
                  sub.getRegisterTimestamp());
              continue;
            }

            subEmptyCount++;
            firePushService.fireOnPushEmpty(sub, dataCenter);
            Loggers.CLIENT_DISABLE_LOG.info(
                "subEmpty,{},{},{}", sub.getDataInfoId(), dataCenter, connectId);
          }
        }
        Loggers.CLIENT_DISABLE_LOG.info("connectId={}, subEmpty={}", connectId, subEmptyCount);
      }
    }

    Map<ConnectId, Collection<Publisher>> pubMap = removeFromSession(connectIdSet, removeSubAndWat);
    for (Entry<ConnectId, Collection<Publisher>> pubEntry : pubMap.entrySet()) {
      clientOffToDataNode(pubEntry.getKey(), pubEntry.getValue());
      Loggers.CLIENT_DISABLE_LOG.info(
          "connectId={}, pubRemove={}", pubEntry.getKey(), pubEntry.getValue().size());
    }
  }

  public boolean isPushEmpty(Subscriber subscriber) {
    // mostly, do not need to push empty
    return false;
  }

  private Map<ConnectId, Collection<Publisher>> removeFromSession(
      Set<ConnectId> connectIds, boolean removeSubAndWat) {
    Map<ConnectId, Collection<Publisher>> ret = Maps.newHashMap();

    if (connectIds != null && connectIds.size() > 0) {
      for (ConnectId connectId : connectIds) {
        Collection<Publisher> publishers = publisherStore.delete(connectId);
        if (publishers != null && publishers.size() > 0) {
          ret.put(connectId, publishers);
        }
        if (removeSubAndWat) {
          subscriberStore.delete(connectId);
          watcherStore.delete(connectId);
        }
      }
    }
    return ret;
  }

  private void clientOffToDataNode(ConnectId connectId, Collection<Publisher> clientOffPublishers) {
    if (CollectionUtils.isEmpty(clientOffPublishers)) {
      return;
    }
    writeDataAcceptor.accept(new ClientOffWriteDataRequest(connectId, clientOffPublishers));
  }

  private final class ClientWatchDog extends LoopRunnable {

    @Override
    public void runUnthrowable() {
      try {
        cleanClientConnect();
      } catch (Throwable e) {
        LOGGER.error("WatchDog failed to cleanClientConnect", e);
      }
    }

    @Override
    public void waitingUnthrowable() {
      ConcurrentUtils.sleepUninterruptibly(5, TimeUnit.SECONDS);
    }
  }

  private final class VersionWatchDog extends WakeUpLoopRunnable {
    boolean prevStopPushSwitch;
    long scanRound;
    long lastScanTimestamp;

    @Override
    public void runUnthrowable() {
      try {
        final int intervalMillis = sessionServerConfig.getScanSubscriberIntervalMillis();
        final boolean stop = !pushSwitchService.canLocalDataCenterPush();
        // could not start scan ver at begin
        // 1. stopPush.val = true default in session.default
        if (stop) {
          SCAN_VER_LOGGER.info("[stopPush]");
          prevStopPushSwitch = true;
          return;
        }
        final long now = System.currentTimeMillis();
        // abs avoid the clock attack
        if (Math.abs(now - lastScanTimestamp) >= intervalMillis || prevStopPushSwitch) {
          try {
            scanSubscribers(scanRound++);
          } finally {
            lastScanTimestamp = System.currentTimeMillis();
          }
        }
        prevStopPushSwitch = false;
      } catch (Throwable e) {
        SCAN_VER_LOGGER.error("WatchDog failed fetch versions", e);
      }
    }

    @Override
    public int getWaitingMillis() {
      return 1000;
    }
  }

  private void scanSubscribers(long round) {

    Set<String> dataCenters = Sets.newLinkedHashSet();

    dataCenters.add(sessionServerConfig.getSessionServerDataCenter());
    dataCenters.addAll(metadataCacheRegistry.getPushEnableDataCenters());

    final long start = System.currentTimeMillis();
    SelectSubscriber selectSubscriber = subscriberStore.selectSubscribers(dataCenters);
    SCAN_VER_LOGGER.info(
        "[select]round={}, regMultiSize={}, span={}",
        round,
        selectSubscriber.toRegisterMulti.size(),
        System.currentTimeMillis() - start);

    regMulti(round, selectSubscriber.toRegisterMulti);

    Map<String, Future<Boolean>> futures = Maps.newHashMapWithExpectedSize(dataCenters.size());
    for (String dataCenter : dataCenters) {
      Future<Boolean> future =
          executorManager
              .getScanExecutor()
              .submit(
                  () -> {
                    try {
                      Map<String, DatumVersion> vers = selectSubscriber.versions.get(dataCenter);
                      List<Subscriber> pushEmpty = selectSubscriber.toPushEmpty.get(dataCenter);
                      SCAN_VER_LOGGER.info(
                          "[scan]dataCenter={}, round={}, interestSize={}, pushEmptySize={}",
                          dataCenter,
                          round,
                          vers.size(),
                          pushEmpty.size(),
                          System.currentTimeMillis() - start);
                      registryScanCallable.scanVersions(
                          round,
                          dataCenter,
                          vers,
                          callableInfo -> {
                            if (subscriberStore.checkInterestVersion(
                                    callableInfo.getDataCenter(),
                                    callableInfo.getDataInfoId(),
                                    callableInfo.getVersion().getValue())
                                .interested) {
                              TriggerPushContext ctx =
                                  new TriggerPushContext(
                                      callableInfo.getDataCenter(),
                                      callableInfo.getVersion().getValue(),
                                      callableInfo.getLeader(),
                                      callableInfo.getCurrentTs());
                              firePushService.fireOnChange(callableInfo.getDataInfoId(), ctx);
                              SCAN_VER_LOGGER.info(
                                  "[fetchSlotVerNotify]round={},{},{},{},{}",
                                  callableInfo.getRound(),
                                  callableInfo.getVersion(),
                                  callableInfo.getDataInfoId(),
                                  callableInfo.getDataCenter(),
                                  callableInfo.getVersion().getValue());
                            }
                          });
                      handlePushEmptySubscribers(dataCenter, pushEmpty);

                      return true;
                    } catch (Throwable th) {
                      SCAN_VER_LOGGER.error(
                          "failed to scan version, dataCenter:{}, round:{}", dataCenter, round, th);
                      return false;
                    }
                  });

      futures.put(dataCenter, future);
    }

    for (Entry<String, Future<Boolean>> entry : futures.entrySet()) {
      try {
        entry.getValue().get(sessionServerConfig.getScanTimeoutMills(), TimeUnit.MILLISECONDS);
      } catch (Throwable th) {
        // return when any datacenter scan timeout
        SCAN_VER_LOGGER.error(
            "scan version timeout, dataCenter:{}, round:{}", entry.getKey(), round, th);
        return;
      }
    }
  }

  private void regMulti(long round, List<Subscriber> toRegisterMulti) {
    for (Subscriber subscriber : toRegisterMulti) {
      try {
        firePushService.fireOnRegister(subscriber);
      } catch (Throwable e) {
        SCAN_VER_LOGGER.error(
            "failed to scan subscribers, round:{}, {}", round, subscriber.shortDesc(), e);
      }
    }
  }

  public String getDataCenterWhenPushEmpty() {
    return sessionServerConfig.getSessionServerDataCenter();
  }

  private void handlePushEmptySubscribers(
      String dataCenter, List<Subscriber> pushEmptySubscribers) {
    for (Subscriber subscriber : pushEmptySubscribers) {
      try {
        if (subscriber.needPushEmpty(dataCenter)) {
          firePushService.fireOnPushEmpty(subscriber, dataCenter);
        }
      } catch (Throwable e) {
        SCAN_VER_LOGGER.error("failed to scan subscribers, {}", subscriber.shortDesc(), e);
      }
    }
  }

  public void cleanClientConnect() {
    Server sessionServer = boltExchange.getServer(sessionServerConfig.getServerPort());
    if (sessionServer == null) {
      LOGGER.warn("server not init when clean connect: {}", sessionServerConfig.getServerPort());
      return;
    }

    Set<ConnectId> connectIndexes = Sets.newHashSetWithExpectedSize(1024 * 8);
    connectIndexes.addAll(publisherStore.getAllConnectId());
    connectIndexes.addAll(subscriberStore.getAllConnectId());
    connectIndexes.addAll(watcherStore.getAllConnectId());

    List<ConnectId> connectIds = new ArrayList<>(64);
    for (ConnectId connectId : connectIndexes) {
      Channel channel =
          sessionServer.getChannel(
              new URL(connectId.getClientHostAddress(), connectId.getClientPort()));
      if (channel == null) {
        connectIds.add(connectId);
      }
    }
    clean(connectIds);
  }

  public static class SelectSubscriber {
    final Map<String /*dataCenter*/, Map<String /*dataInfoId*/, DatumVersion>> versions;

    final Map<String /*dataCenter*/, List<Subscriber>> toPushEmpty;

    final List<Subscriber> toRegisterMulti;

    public SelectSubscriber(
        Map<String, Map<String, DatumVersion>> versions,
        Map<String, List<Subscriber>> toPushEmpty,
        List<Subscriber> toRegisterMulti) {
      this.versions = versions;
      this.toPushEmpty = toPushEmpty;
      this.toRegisterMulti = toRegisterMulti;
    }

    /**
     * Getter method for property <tt>versions</tt>.
     *
     * @return property value of versions
     */
    public Map<String, Map<String, DatumVersion>> getVersions() {
      return versions;
    }

    /**
     * Getter method for property <tt>toPushEmpty</tt>.
     *
     * @return property value of toPushEmpty
     */
    public Map<String, List<Subscriber>> getToPushEmpty() {
      return toPushEmpty;
    }

    /**
     * Getter method for property <tt>toRegisterMulti</tt>.
     *
     * @return property value of toRegisterMulti
     */
    public List<Subscriber> getToRegisterMulti() {
      return toRegisterMulti;
    }
  }
}

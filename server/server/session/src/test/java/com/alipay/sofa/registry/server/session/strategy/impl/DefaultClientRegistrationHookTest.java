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
package com.alipay.sofa.registry.server.session.strategy.impl;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alipay.sofa.registry.common.model.Tuple;
import com.alipay.sofa.registry.common.model.metaserver.ProvideData;
import com.alipay.sofa.registry.common.model.store.BaseInfo;
import com.alipay.sofa.registry.common.model.store.Publisher;
import com.alipay.sofa.registry.common.model.store.Watcher;
import com.alipay.sofa.registry.server.session.AbstractSessionServerTestBase;
import com.alipay.sofa.registry.server.session.TestUtils;
import com.alipay.sofa.registry.server.session.providedata.ConfigProvideDataWatcher;
import com.alipay.sofa.registry.server.session.push.FirePushService;
import com.alipay.sofa.registry.server.session.push.PushSwitchService;
import com.alipay.sofa.registry.server.session.registry.DefaultClientRegistrationHook;
import com.alipay.sofa.registry.server.session.store.Watchers;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** */
public class DefaultClientRegistrationHookTest extends AbstractSessionServerTestBase {

  private final String dataId = "testWatcherDataId";

  @Before
  public void before() {
    sessionServerConfig.setWatchConfigEnable(false);
    sessionServerConfig.setScanWatcherIntervalMillis(10);
  }

  @Test
  public void testAfterPublisherRegister() {
    DefaultClientRegistrationHook clientRegistrationHook =
        new DefaultClientRegistrationHook(
            sessionServerConfig,
            mock(FirePushService.class),
            mock(PushSwitchService.class),
            mock(ConfigProvideDataWatcher.class),
            mock(Watchers.class));
    clientRegistrationHook.afterClientRegister(new Publisher());
  }

  @Test
  public void testAfterWatcherRegisterDisable() {
    FirePushService firePushService = mock(FirePushService.class);
    PushSwitchService pushSwitchService = mock(PushSwitchService.class);
    Watchers sessionWatchers = mock(Watchers.class);
    ConfigProvideDataWatcher configProvideDataWatcher = mock(ConfigProvideDataWatcher.class);
    DefaultClientRegistrationHook clientRegistrationHook =
        new DefaultClientRegistrationHook(
            sessionServerConfig,
            firePushService,
            pushSwitchService,
            configProvideDataWatcher,
            sessionWatchers);

    Watcher w = TestUtils.newWatcher(dataId);
    clientRegistrationHook.afterClientRegister(w);

    verify(firePushService, times(0)).fireOnWatcher(any(), any());

    when(pushSwitchService.canIpPush(anyString())).thenReturn(true);
    clientRegistrationHook.afterClientRegister(w);
    verify(firePushService, times(1)).fireOnWatcher(any(), any());
    verify(configProvideDataWatcher, times(0)).watch(any());
  }

  @Test
  public void testAfterWatcherRegisterEnable() {
    sessionServerConfig.setWatchConfigEnable(true);
    FirePushService firePushService = mock(FirePushService.class);
    PushSwitchService pushSwitchService = mock(PushSwitchService.class);
    Watchers sessionWatchers = mock(Watchers.class);
    ConfigProvideDataWatcher configProvideDataWatcher = mock(ConfigProvideDataWatcher.class);
    DefaultClientRegistrationHook clientRegistrationHook =
        new DefaultClientRegistrationHook(
            sessionServerConfig,
            firePushService,
            pushSwitchService,
            configProvideDataWatcher,
            sessionWatchers);

    Watcher w = TestUtils.newWatcher(dataId);
    clientRegistrationHook.afterClientRegister(w);
    verify(configProvideDataWatcher, times(1)).watch(any());
    verify(firePushService, times(0)).fireOnWatcher(any(), any());

    when(pushSwitchService.canIpPush(anyString())).thenReturn(true);
    clientRegistrationHook.afterClientRegister(w);
    verify(firePushService, times(0)).fireOnWatcher(any(), any());

    when(configProvideDataWatcher.get(anyString()))
        .thenReturn(new ProvideData(null, "dataId", 100L));
    clientRegistrationHook.afterClientRegister(w);
    verify(firePushService, times(1)).fireOnWatcher(any(), any());
  }

  @Test
  public void testFilter() {
    FirePushService firePushService = mock(FirePushService.class);
    PushSwitchService pushSwitchService = mock(PushSwitchService.class);
    Watchers sessionWatchers = mock(Watchers.class);
    ConfigProvideDataWatcher configProvideDataWatcher = mock(ConfigProvideDataWatcher.class);
    DefaultClientRegistrationHook clientRegistrationHook =
        new DefaultClientRegistrationHook(
            sessionServerConfig,
            firePushService,
            pushSwitchService,
            configProvideDataWatcher,
            sessionWatchers);

    Assert.assertNull(clientRegistrationHook.filter());
    Watcher w = TestUtils.newWatcher(dataId);
    when(sessionWatchers.getDataList()).thenReturn(Collections.singletonList(w));
    Tuple<Set<String>, List<Watcher>> t = clientRegistrationHook.filter();
    Assert.assertEquals(t.o1, Sets.newHashSet(w.getDataInfoId()));
    Assert.assertEquals(t.o2.get(0), w);
    sessionServerConfig.setWatchConfigEnable(true);
    clientRegistrationHook.processWatch();

    w.setClientVersion(BaseInfo.ClientVersion.MProtocolpackage);
    t = clientRegistrationHook.filter();
    Assert.assertEquals(0, t.o1.size());
    Assert.assertEquals(0, t.o2.size());

    clientRegistrationHook.processWatch();
    clientRegistrationHook.afterClientRegister(w);
  }

  @Test
  public void testProcess() {
    FirePushService firePushService = mock(FirePushService.class);
    PushSwitchService pushSwitchService = mock(PushSwitchService.class);
    Watchers sessionWatchers = mock(Watchers.class);
    ConfigProvideDataWatcher configProvideDataWatcher = mock(ConfigProvideDataWatcher.class);
    DefaultClientRegistrationHook clientRegistrationHook =
        new DefaultClientRegistrationHook(
            sessionServerConfig,
            firePushService,
            pushSwitchService,
            configProvideDataWatcher,
            sessionWatchers);

    Watcher w = TestUtils.newWatcher(dataId);
    Assert.assertTrue(clientRegistrationHook.processWatchWhenWatchConfigDisable(w));
    verify(firePushService, times(1)).fireOnWatcher(any(), any());
    w.updatePushedVersion(10);
    Assert.assertFalse(clientRegistrationHook.processWatchWhenWatchConfigDisable(w));

    // reset watcher
    w = TestUtils.newWatcher(dataId);
    Assert.assertFalse(clientRegistrationHook.processWatchWhenWatchConfigEnable(w));
    ProvideData data = new ProvideData(null, dataId, 10L);
    when(configProvideDataWatcher.get(anyString())).thenReturn(data);
    Assert.assertTrue(clientRegistrationHook.processWatchWhenWatchConfigEnable(w));
    verify(firePushService, times(2)).fireOnWatcher(any(), any());
    w.updatePushedVersion(10);

    Assert.assertFalse(clientRegistrationHook.processWatchWhenWatchConfigEnable(w));
    verify(firePushService, times(2)).fireOnWatcher(any(), any());

    data = new ProvideData(null, dataId, 20L);
    when(configProvideDataWatcher.get(anyString())).thenReturn(data);
    Assert.assertTrue(clientRegistrationHook.processWatchWhenWatchConfigEnable(w));
    verify(firePushService, times(3)).fireOnWatcher(any(), any());
    w.updatePushedVersion(20);
    Assert.assertFalse(clientRegistrationHook.processWatch(w, false));
    Assert.assertFalse(clientRegistrationHook.processWatch(w, true));
  }
}

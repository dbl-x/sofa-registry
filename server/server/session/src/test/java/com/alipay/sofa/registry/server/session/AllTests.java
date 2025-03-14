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
package com.alipay.sofa.registry.server.session;

/**
 * @author chen.zhu
 *     <p>Apr 12, 2021
 */
import com.alipay.sofa.registry.server.session.interceptor.OrderedInterceptorManagerTest;
import com.alipay.sofa.registry.server.session.node.service.MetaServerServiceImplTest;
import com.alipay.sofa.registry.server.session.node.service.SessionMetaServerManagerTest;
import com.alipay.sofa.registry.server.session.slot.SlotTableCacheImplTest;
import com.alipay.sofa.registry.server.session.store.DataCacheTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({
  OrderedInterceptorManagerTest.class,
  DataCacheTest.class,
  SlotTableCacheImplTest.class,
  MetaServerServiceImplTest.class,
  SessionMetaServerManagerTest.class
})
public class AllTests {}

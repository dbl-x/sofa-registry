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
package com.alipay.sofa.registry.server.session.acceptor;

import com.alipay.sofa.registry.common.model.ConnectId;

/**
 * @author kezhu.wukz
 * @author shangyu.wh
 * @version 1.0: WriteDataRequest.java, v 0.1 2019-06-06 18:42 shangyu.wh Exp $
 */
public interface WriteDataRequest<T> {

  /**
   * ConnectId.
   *
   * @return ConnectId
   */
  ConnectId getConnectId();

  /**
   * Type of the request.
   *
   * @return WriteDataRequestType
   */
  WriteDataRequestType getRequestType();

  /**
   * Gets request body.
   *
   * @return the request body
   */
  T getRequestBody();

  /** The enum for request type. */
  enum WriteDataRequestType {
    PUBLISHER,
    UN_PUBLISHER,
    CLIENT_OFF
  }
}

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.salesforce.hbase.stats.impl;

import java.util.Arrays;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.statistics.StatisticTracker;

/**
 * Utilities for {@link StatisticTracker}s.
 */
public class TrackerUtil {

  private TrackerUtil() {
    // private ctor for utils
  }

  public static byte[] copyRow(KeyValue kv) {
    return Arrays.copyOfRange(kv.getBuffer(), kv.getRowOffset(),
      kv.getRowOffset() + kv.getRowLength());
  }

}
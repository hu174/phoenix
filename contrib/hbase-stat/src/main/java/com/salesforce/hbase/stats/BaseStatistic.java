/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 */
package com.salesforce.hbase.stats;

import java.io.IOException;
import java.util.List;

import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionObserver;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.KeyValueScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreScanner;
import org.apache.hadoop.hbase.statistics.StatisticTracker;
import org.apache.hadoop.hbase.statistics.StatisticsTable;
import org.apache.hadoop.hbase.util.Bytes;


/**
 * Simple helper base class for all {@link RegionObserver RegionObservers} that need to access a
 * {@link StatisticsTable}.
 */
public abstract class BaseStatistic extends BaseRegionObserver implements StatisticTracker {

  protected StatisticsTable stats;

  @Override
  public void start(CoprocessorEnvironment e) throws IOException {
    HTableDescriptor desc = ((RegionCoprocessorEnvironment) e).getRegion().getTableDesc();
    stats = StatisticsTable.getStatisticsTableForCoprocessor(e, desc.getNameAsString());
  }

  @Override
  public void stop(CoprocessorEnvironment e) throws IOException {
    stats.close();
  }

  @Override
  public InternalScanner preCompactScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c,
      Store store, List<? extends KeyValueScanner> scanners, ScanType scanType, long earliestPutTs,
      InternalScanner s) throws IOException {
    InternalScanner internalScan = s;
    if (scanType.equals(ScanType.MAJOR_COMPACT)) {
      // this is the first CP accessed, so we need to just create a major compaction scanner, just
      // like in the compactor
      if (s == null) {
        Scan scan = new Scan();
        scan.setMaxVersions(store.getFamily().getMaxVersions());
        long smallestReadPoint = store.getHRegion().getSmallestReadPoint();
        internalScan =
            new StoreScanner(store, store.getScanInfo(), scan, scanners, scanType,
                smallestReadPoint, earliestPutTs);
      }
      InternalScanner scanner = getInternalScanner(c, store, internalScan,
        store.getColumnFamilyName());
      if (scanner != null) {
        internalScan = scanner;
      }
    }
    return internalScan;
  }

  /**
   * Get an internal scanner that will update statistics. This should be a delegating
   * {@link InternalScanner} so the original scan semantics are preserved. You should consider using
   * the {@link StatScanner} for the delegating scanner.
   * @param c
   * @param store
   * @param internalScan
   * @return <tt>null</tt> if the existing scanner is sufficient, otherwise the scanner to use going
   *         forward
   */
  protected InternalScanner getInternalScanner(ObserverContext<RegionCoprocessorEnvironment> c,
      Store store, InternalScanner internalScan, String family) {
    return new StatScanner(this, stats, c.getEnvironment().getRegion()
        .getRegionInfo(), internalScan, Bytes.toBytes(family));
  }
}
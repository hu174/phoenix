package com.salesforce.phoenix.index;

import java.io.IOException;

import org.apache.hadoop.hbase.KeyValue;

import com.salesforce.hbase.index.builder.covered.Scanner;

/**
 * {@link Scanner} that has no underlying data
 */
public class EmptyScanner implements Scanner {

  @Override
  public KeyValue next() throws IOException {
    return null;
  }

  @Override
  public boolean seek(KeyValue next) throws IOException {
    return false;
  }

  @Override
  public KeyValue peek() throws IOException {
    return null;
  }
}
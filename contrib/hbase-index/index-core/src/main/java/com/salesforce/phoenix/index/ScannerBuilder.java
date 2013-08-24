package com.salesforce.phoenix.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.FamilyFilter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.QualifierFilter;
import org.apache.hadoop.hbase.regionserver.ExposedMemStore;
import org.apache.hadoop.hbase.util.Bytes;

import com.google.common.collect.Lists;
import com.salesforce.hbase.index.builder.covered.ColumnReference;
import com.salesforce.hbase.index.builder.covered.ColumnTracker;
import com.salesforce.hbase.index.builder.covered.Scanner;
import com.salesforce.hbase.index.builder.covered.util.FilteredKeyValueScanner;

/**
 *
 */
public class ScannerBuilder {

  private ExposedMemStore memstore;
  private Mutation update;


  public ScannerBuilder(ExposedMemStore memstore, Mutation update) {
    this.memstore = memstore;
    this.update = update;
  }

  public Scanner buildIndexedColumnScanner(Collection<KeyValue> kvs,
      Collection<? extends ColumnReference> indexedColumns, ColumnTracker tracker, long ts) {
    
    //check to see if the kvs in the new update even match any of the columns requested
    //assuming that for any index, there are going to small number of columns, versus the number of kvs in any one batch.
    boolean matches = false;
    outer: for (KeyValue kv : kvs) {
      for (ColumnReference ref : indexedColumns) {
        if (ref.matchesFamily(kv.getFamily()) && ref.matchesQualifier(kv.getQualifier())) {
          matches = true;
          // if a single column matches a single kv, we need to build a whole scanner
          break outer;
        }
      }
    }
    // no indexed column matches any of the updates, so we don't need to scan the current table
    // state
    if (!matches) {
      return new EmptyScanner();
    }

    Filter columnFilters = getColumnFilters(indexedColumns);
    FilterList filters = new FilterList(Lists.newArrayList(columnFilters));

    // skip to the right TS. This needs to come before the deletes since the deletes will hide any
    // state that comes before the actual kvs, so we need to capture those TS as they change the row
    // state.
    filters.addFilter(new ColumnTrackingNextLargestTimestampFilter(ts, tracker));

    // filter out kvs based on deletes
    filters.addFilter(new ApplyAndFilterDeletesFilter(getAllFamilies(indexedColumns)));

    // combine the family filters and the rest of the filters as a
    return getFilteredScanner(filters);
  }

  /**
   * @param columns
   * @param ts
   * @return
   */
  public Scanner buildNonIndexedColumnsScanner(List<? extends ColumnReference> columns, long ts) {
    Filter columnFilters = getColumnFilters(columns);
    FilterList filters = new FilterList(Lists.newArrayList(columnFilters));
    // filter out things with a newer timestamp
    filters.addFilter(new MaxTimestampFilter(ts));
    // filter out kvs based on deletes
    List<byte[]> families = getAllFamilies(columns);
    filters.addFilter(new ApplyAndFilterDeletesFilter(families));
    return getFilteredScanner(filters);
  }

  /**
   * @param columns columns to filter
   * @return filter that will skip any {@link KeyValue} that doesn't match one of the passed columns
   *         and the
   */
  private Filter
      getColumnFilters(Collection<? extends ColumnReference> columns) {
    // each column needs to be added as an OR, so we need to separate them out
    FilterList columnFilters = new FilterList(FilterList.Operator.MUST_PASS_ONE);

    // create a filter that matches each column reference
    for (ColumnReference ref : columns) {
      Filter columnFilter =
          new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(ref.getFamily()));
      // combine with a match for the qualifier, if the qualifier is a specific qualifier
      if (!Bytes.equals(ColumnReference.ALL_QUALIFIERS, ref.getQualifier())) {
        columnFilter =
            new FilterList(columnFilter, new QualifierFilter(CompareOp.EQUAL, new BinaryComparator(
                ref.getQualifier())));
      }
      columnFilters.addFilter(columnFilter);
    }
    return columnFilters;
  }

  private List<byte[]> getAllFamilies(Collection<? extends ColumnReference> columns) {
    List<byte[]> families = new ArrayList<byte[]>(columns.size());
    for (ColumnReference ref : columns) {
      families.add(ref.getFamily());
    }
    return families;
  }

  private Scanner getFilteredScanner(Filter filters) {
    // create a scanner and wrap it as an iterator, meaning you can only go forward
    final FilteredKeyValueScanner kvScanner = new FilteredKeyValueScanner(filters, memstore);
    // seek the scanner to initialize it
    KeyValue start = KeyValue.createFirstOnRow(update.getRow());
    try {
      if (!kvScanner.seek(start)) {
        return new EmptyScanner();
      }
    } catch (IOException e) {
      // This should never happen - everything should explode if so.
      throw new RuntimeException(
          "Failed to seek to first key from update on the memstore scanner!", e);
    }

    // we have some info in the scanner, so wrap it in an iterator and return.
    return new Scanner() {

      @Override
      public KeyValue next() {
        try {
          return kvScanner.next();
        } catch (IOException e) {
          throw new RuntimeException("Error reading kvs from local memstore!");
        }
      }

      @Override
      public boolean seek(KeyValue next) throws IOException {
        // check to see if the next kv is after the current key, in which case we can use reseek,
        // which will be more efficient
        KeyValue peek = kvScanner.peek();
        // there is another value and its before the requested one - we can do a reseek!
        if (peek != null) {
          int compare = KeyValue.COMPARATOR.compare(peek, next);
          if (compare < 0) {
            return kvScanner.reseek(next);
          } else if (compare == 0) {
            // we are already at the given key!
            return true;
          }
        }
        return kvScanner.seek(next);
      }

      @Override
      public KeyValue peek() throws IOException {
        return kvScanner.peek();
      }
    };
  }
}
package com.salesforce.hbase.index.builder.covered;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.ArrayUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.regionserver.ExposedMemStore;
import org.apache.hadoop.hbase.regionserver.KeyValueScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;

import com.salesforce.hbase.index.builder.covered.util.FamilyOnlyFilter;
import com.salesforce.hbase.index.builder.covered.util.FilteredKeyValueScanner;
import com.salesforce.hbase.index.builder.covered.util.NewerTimestampFilter;

/**
 * Handle serialization to/from a column-covered index.
 * @see CoveredColumnIndexer
 */
public class CoveredColumnIndexCodec {
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  public static final byte[] INDEX_ROW_COLUMN_FAMILY = Bytes.toBytes("ROW");
  private static final Configuration conf = HBaseConfiguration.create();
  {
    // keep it all on the heap - hopefully this should be a bit faster and shouldn't need to grow
    // very large as we are just handling a single row.
    conf.setBoolean("hbase.hregion.memstore.mslab.enabled", false);
  }

  private ColumnGroup group;
  private ExposedMemStore memstore;
  private byte[] pk;

  public CoveredColumnIndexCodec(Result currentRow, ColumnGroup group) {
    this.pk = currentRow.getRow();
    this.group = group;
    this.memstore = new ExposedMemStore(conf, KeyValue.COMPARATOR);
    addAll(currentRow.list());
  }

  /**
   * Add all the {@link KeyValue}s in the list to the memstore. This is just a small utility method
   * around {@link ExposedMemStore#add(KeyValue)} to make it easier to deal with batches of
   * {@link KeyValue}s.
   * @param list keyvalues to add
   */
  private void addAll(List<KeyValue> list) {
    for (KeyValue kv : list) {
      this.memstore.add(kv);
    }
  }

  /**
   * Add a {@link Put} to the values stored for the current row
   * @param pendingUpdate pending update to the current row - will appear first in the
   *          {@link ValueMap}.
   */
  public void addUpdate(Mutation pendingUpdate) {
    for (Map.Entry<byte[], List<KeyValue>> e : pendingUpdate.getFamilyMap().entrySet()) {
      List<KeyValue> edits = e.getValue();
      addAll(edits);
    }
  }

  /**
   * Get the most recent value for each column group, in the order of the columns stored in the
   * group and then build them into a single byte array to use as row key for an index update for
   * the column group.
   * @return the row key and the corresponding list of {@link CoveredColumn}s to the position of
   *         their value in the row key
   */
  public Pair<byte[], List<CoveredColumn>> getIndexRowKey() {
    return getIndexRowKey(Long.MAX_VALUE);
  }

  /**
   * Get the most recent value for each column group, less than the specified timestamps, in the
   * order of the columns stored in the group and then build them into a single byte array to use as
   * the row key for an index update for the column group.
   * @return the row key and the corresponding list of {@link CoveredColumn}s to the position of
   *         their value in the row key
   */
  public Pair<byte[], List<CoveredColumn>> getIndexRowKey(long timestamp) {
    int length = 0;
    List<byte[]> topValues = new ArrayList<byte[]>();
    // columns that match against values, as we find them
    List<CoveredColumn> columns = new ArrayList<CoveredColumn>();

    // filter out the for anything older than what we want
    NewerTimestampFilter timestamps = new NewerTimestampFilter(timestamp);

    // go through each group,in order, to find the matching value (or none)
    for (CoveredColumn column : group) {
      final byte[] family = Bytes.toBytes(column.family);
      // filter families that aren't what we are looking for
      FamilyOnlyFilter familyFilter = new FamilyOnlyFilter(new BinaryComparator(family));
      // join the filters so we only include things that match both (correct family and the TS is
      // older than the given ts)
      Filter filter = new FilterList(timestamps, familyFilter);
      KeyValueScanner scanner = new FilteredKeyValueScanner(filter, this.memstore);

      /*
       * now we have two possibilities. (1) the CoveredColumn has a specific column - this is the
       * easier one, we can just seek down to that keyvalue and then pull the next one out. If there
       * aren't any keys, we just inject a null value and point at the coveredcolumn, or (2) it
       * includes all qualifiers - we need to match all column families, but only inject the null
       * mapping if its the first key
       */

      // key to seek. We can only seek to the family because we may have a family delete on top that
      // covers everything below it, which we would miss if we seek right to the family:qualifier
      KeyValue first = KeyValue.createFirstOnRow(pk, Bytes.toBytes(column.family), null);
      try {
        // seek to right before the key in the scanner
        byte[] value = EMPTY_BYTE_ARRAY;
        // no values, so add a null against the entire CoveredColumn
        if (!scanner.seek(first)) {
          topValues.add(value);
          columns.add(column);
          continue;
        }

        byte[] prevCol = null;
        // not null because seek() returned true
        KeyValue next = scanner.next();
        boolean done = false;
        do {
          byte[] qual = next.getQualifier();
          boolean columnMatches = column.matchesQualifier(qual);

          /*
           * check delete to see if we can just replace this with a single delete if its a family
           * delete then we have deleted all columns and are definitely done with this
           * coveredcolumn. This works because deletes will always sort first, so we can be sure
           * that if we see a delete, we can skip everything else.
           */
          if (next.isDeleteFamily()) {
            // count it as a non-match for all rows, so we add a single null for the entire column
            value = EMPTY_BYTE_ARRAY;
            break;
          } else if (columnMatches) {
            // we are deleting a single column/kv
            if (next.isDelete()) {
              value = EMPTY_BYTE_ARRAY;
            } else {
              value = next.getValue();
            }
            done = true;
            // we are covering a single column, then we are done.
            if (column.allColumns()) {
              /*
               * we are matching all columns, so we need to make sure that this is a new qualifier.
               * If its a new qualifier, then we want to add that value, but otherwise we can skip
               * ahead to the next key.
               */
              if (prevCol == null || !Bytes.equals(prevCol, qual)) {
                prevCol = qual;
              } else {
                continue;
              }
            }
          }

          // add the array to the list
          length += value.length;
          topValues.add(value);
          columns.add(column);
          // only go around again if there is more data and we are matching against all column
        } while ((!done || column.allColumns()) && (next = scanner.next()) != null);

        // we never found a match, so we need to add an empty entry
        if (!done) {
          length += value.length;
          topValues.add(value);
          columns.add(column);
        }

      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    byte[] key = CoveredColumnIndexCodec.composeRowKey(pk, length, topValues);
    return new Pair<byte[], List<CoveredColumn>>(key, columns);
  }

  /**
   * Add each {@link ColumnGroup} to a {@link Put} under a single column family. Each value stored
   * in the key is matched to a column group - value 1 matches family:qualfier 1. This holds true
   * even if the {@link ColumnGroup} matches all columns in the family.
   * <p>
   * Columns are added as:
   * 
   * <pre>
   * &lt{@value CoveredColumnIndexCodec#INDEX_ROW_COLUMN_FAMILY}&gt | &lti&gt[covered column family]:[covered column qualifier] | &lttimestamp&gt | <tt>null</tt>
   * </pre>
   * 
   * where "i" is the integer index matching the index of the value in the row key, serialized as a
   * byte, and [covered column family]:[covered column qualifier] is the serialization returned by
   * {@link CoveredColumnIndexCodec#toIndexQualfier(CoveredColumn)}
   * @param list
   * @param indexInsert {@link Put} to update with the family:qualifier of each matching value.
   * @param family column family under which to store the columns. The same column is used for all
   *          columns
   * @param timestamp timestamp at which to include the columns in the {@link Put}
   * @param sortedKeys a collection of the keys from the {@link ValueMap} that can be used to search
   *          the value may for a given group.
   */
  public Put getPutToIndex(long timestamp) {
    Pair<byte[], List<CoveredColumn>> indexRow = this.getIndexRowKey();
    Put indexInsert = new Put(indexRow.getFirst());
    // add each of the corresponding families to the put
    int count = 0;
    for (CoveredColumn column : indexRow.getSecond()) {
      indexInsert.add(INDEX_ROW_COLUMN_FAMILY,
        ArrayUtils.addAll(Bytes.toBytes(count++), toIndexQualifier(column)), timestamp,
        null);
    }
    return indexInsert;
  }

  private static byte[] toIndexQualifier(CoveredColumn column) {
    return ArrayUtils.addAll(Bytes.toBytes(column.family + CoveredColumn.SEPARATOR),
      column.qualifier);
  }

  /**
   * Compose the final index row key.
   * <p>
   * This is faster than adding each value independently as we can just build a single a array and
   * copy everything over once.
   * @param pk primary key of the original row
   * @param length total number of bytes of all the values that should be added
   * @param values to use when building the key
   * @return
   */
  static byte[] composeRowKey(byte[] pk, int length, List<byte[]> values) {
    // now build up expected row key, each of the values, in order, followed by the PK and then some
    // info about lengths so we can deserialize each value
    byte[] output = new byte[length + pk.length];
    int pos = 0;
    int[] lengths = new int[values.size()];
    int i = 0;
    for (byte[] v : values) {
      System.arraycopy(v, 0, output, pos, v.length);
      lengths[i++] = v.length;
      pos += v.length;
    }
  
    // add the primary key to the end of the row key
    System.arraycopy(pk, 0, output, pos, pk.length);
  
    // add the lengths as suffixes so we can deserialize the elements again
    for (int l : lengths) {
      output = ArrayUtils.addAll(output, Bytes.toBytes(l));
    }
  
    // and the last integer is the number of values
    return ArrayUtils.addAll(output, Bytes.toBytes(values.size()));
  }

  /**
   * Get the values for each the columns that were stored in the row key from calls to
   * {@link #getPutToIndex(long)} or {@link #composeRowKey(byte[], int, List)}, in the order they
   * were stored.
   * @param bytes bytes that were written by this codec
   * @return the list of values for the columns
   */
  public static List<byte[]> getValues(byte[] bytes) {
    // get the total number of keys in the bytes
    int keyCount = CoveredColumnIndexCodec.getPreviousInteger(bytes, bytes.length);
    List<byte[]> keys = new ArrayList<byte[]>(keyCount);
    int[] lengths = new int[keyCount];
    int lengthPos = keyCount - 1;
    int pos = bytes.length - Bytes.SIZEOF_INT;
    // figure out the length of each key
    for (int i = 0; i < keyCount; i++) {
      lengths[lengthPos--] = CoveredColumnIndexCodec.getPreviousInteger(bytes, pos);
      pos -= Bytes.SIZEOF_INT;
    }

    int current = 0;
    for (int length : lengths) {
      byte[] key = Arrays.copyOfRange(bytes, current, current + length);
      keys.add(key);
      current += length;
    }
    
    return keys;
  }

  /**
   * Check to see if a row key created with {@link composeRowKey} just contains
   * a list of null values.
   * @return <tt>true</tt> if all the values are zero-length, <tt>false</tt> otherwise
   */
  public static boolean checkRowKeyForAllNulls(byte[] bytes) {
    int keyCount = CoveredColumnIndexCodec.getPreviousInteger(bytes, bytes.length);
    int pos = bytes.length - Bytes.SIZEOF_INT;
    for (int i = 0; i < keyCount; i++) {
      int next = CoveredColumnIndexCodec.getPreviousInteger(bytes, pos);
      if (next > 0) {
        return false;
      }
      pos -= Bytes.SIZEOF_INT;
    }
  
    return true;
  }

  /**
   * Read an integer from the preceding {@value Bytes#SIZEOF_INT} bytes
   * @param bytes array to read from
   * @param start start point, backwards from which to read. For example, if specifying "25", we
   *          would try to read an integer from 21 -> 25
   * @return an integer from the proceeding {@value Bytes#SIZEOF_INT} bytes, if it exists.
   */
  private static int getPreviousInteger(byte[] bytes, int start) {
    return Bytes.toInt(bytes, start - Bytes.SIZEOF_INT);
  }
}
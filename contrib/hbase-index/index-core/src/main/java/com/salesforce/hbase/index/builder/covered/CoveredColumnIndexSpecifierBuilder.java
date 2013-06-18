package com.salesforce.hbase.index.builder.covered;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;

import com.salesforce.hbase.index.IndexUtil;

/**
 *
 */
public class CoveredColumnIndexSpecifierBuilder {

  private static final String INDEX_TO_TABLE_CONF_PREFX = "hbase.index.covered.";
  // number of index 'groups'. Each group represents a set of 'joined' columns. The data stored with
  // each joined column are either just the columns in the group or all the most recent data in the
  // row (a fully covered index).
  private static final String COUNT = ".count";
  private static final String INDEX_GROUPS_COUNT_KEY = INDEX_TO_TABLE_CONF_PREFX + ".groups" + COUNT;
  private static final String INDEX_GROUP_PREFIX = INDEX_TO_TABLE_CONF_PREFX + "group.";
  private static final String INDEX_GROUP_COVERAGE_SUFFIX = ".columns";
  private static final String TABLE_SUFFIX = ".table";

  // right now, we don't support this should be easy enough to add later
  // private static final String INDEX_GROUP_FULLY_COVERED = ".covered";

  Map<String, String> specs = new HashMap<String, String>();

  /**
   * Add a group of columns to index
   * @param columns Pairs of cf:cq (full specification of a column) to index
   * @return the group number of the index configuration. This can be used to remove a group later
   */
  public int addIndexGroup(ColumnGroup columns) {
    if (columns == null || columns.size() == 0) {
      throw new IllegalArgumentException("Must specify some columns to index!");
    }
    // hbase.index.covered.groups = i
    String index = specs.get(INDEX_GROUPS_COUNT_KEY); 
    int currentCount = Integer.parseInt(index == null ? "-1" : index);
    int next = currentCount++;

    // hbase.index.covered.group.<i>
    String prefix = INDEX_GROUP_PREFIX + Integer.toString(next);

    // set the table to which the group writes
    // hbase.index.covered.group.<i>.table
    specs.put(prefix + TABLE_SUFFIX, columns.getTable());
    
    // a different key for each column in the group
    // hbase.index.covered.group.<i>.columns
    String columnPrefix = prefix + INDEX_GROUP_COVERAGE_SUFFIX;
    // hbase.index.covered.group.<i>.columns.count = <j>
    String columnsSizeKey = columnPrefix + COUNT;
    specs.put(columnsSizeKey, Integer.toString(columns.size()));
    
    // add each column in the group
    int i=0; 
    for (CoveredColumn column : columns) {
      // hbase.index.covered.group.<i>.columns.<j>
      String nextKey = columnPrefix + "." + Integer.toString(i);
      String nextValue = column.serialize();
      specs.put(nextKey, nextValue);
      i++;
    }

    // update the group count
    // hbase.index.covered.groups = i++
    specs.put(INDEX_GROUPS_COUNT_KEY, Integer.toString(next));
    return next;
  }

  public void setupIndexing(HTableDescriptor desc) throws IOException {
    IndexUtil.enableIndexing(desc, CoveredColumnIndexer.class, specs);
  }

  static List<ColumnGroup> getColumns(Configuration conf) {
    int count= conf.getInt(INDEX_GROUPS_COUNT_KEY, 0);
    if (count ==0) {
      return Collections.emptyList();
    }

    // parse out all the column groups we should index
    List<ColumnGroup> columns = new ArrayList<ColumnGroup>(count);
    for (int i = 0; i < count; i++) {
      // parse out each group
      String prefix = INDEX_GROUP_PREFIX + i;

      // hbase.index.covered.group.<i>.table
      String table = conf.get(prefix + TABLE_SUFFIX);
      ColumnGroup group = new ColumnGroup(table);

      // parse out each column in the group
      // hbase.index.covered.group.<i>.columns
      String columnPrefix = prefix + INDEX_GROUP_COVERAGE_SUFFIX;
      // hbase.index.covered.group.<i>.columns.count = j
      String columnsSizeKey = columnPrefix + COUNT;
      int columnCount = conf.getInt(columnsSizeKey, 0);
      for(int j=0; j< columnCount; j++){
        CoveredColumn column = CoveredColumn.parse(columnPrefix + "." + j);
        group.add(column);
      }

      // add the group
      columns.add(group);
    }
    return columns;
  }
}
/*
 * Copyright 2012 Shoji Nishimura
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package tiny.mdhbase;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.util.Bytes;

import com.google.common.io.Closeables;
import tiny.mdhbase.util.Utils;

/**
 * Index
 * 
 * Index maintains partitioned spaces. When the number of points in a sub-space
 * exceeds a split threshold, the index halves the sub-space and allocates two
 * new buckets for the partitioned sub-spaces.
 * 
 * Schema:
 * <ul>
 * <li>row key: min key of a bucket
 * <li>column family: info
 * <ul>
 * <li>column: pl, common prefix length of points in a bucket
 * <li>column: bs, size of a bucket/number of points in a bucket
 * </ul>
 * </ul>
 * 
 * Bucket name, which is named after the common prefix naming scheme is
 * represented as a pair of binary value and its prefix length. For example.
 * [011*****] is represented as a pair of [01100000] and 3.
 * 
 * 
 * @author shoji
 * 
 */
public class Index implements Closeable {
  public static final byte[] FAMILY_INFO = Bytes.toBytes("info");

  public static final byte[] COLUMN_PREFIX_LENGTH = Bytes.toBytes("pl");

  public static final byte[] COLUMN_BUCKET_SIZE = Bytes.toBytes("bs");

  private final int splitThreshold;

  private int splitTimes;

  private final HTable dataTable;

  private final HTable indexTable;

  private final Admin admin;

  public Index(Configuration config, String tableNameStr, int splitThreshold)
      throws IOException {

    Connection connection = ConnectionFactory.createConnection(config);
    this.admin = connection.getAdmin();
    TableName tableName = TableName.valueOf(tableNameStr);
    if (!admin.tableExists(tableName)) {
      HTableDescriptor tdesc = new HTableDescriptor(tableName);
      HColumnDescriptor cdesc = new HColumnDescriptor(Bucket.FAMILY);
      tdesc.addFamily(cdesc);
      admin.createTable(tdesc);
    }
    dataTable = new HTable(config, tableName);

    String indexTableStr = tableNameStr + "BS"+splitThreshold+"_index";
    TableName indexTableName = TableName.valueOf(indexTableStr);
    if (!admin.tableExists(indexTableName)) {
      HTableDescriptor tdesc = new HTableDescriptor(indexTableName);
      HColumnDescriptor cdesc = new HColumnDescriptor(Index.FAMILY_INFO);
      tdesc.addFamily(cdesc);
      admin.createTable(tdesc);

      indexTable = new HTable(config, indexTableName);
      Put put = new Put(Utils.bitwiseZip(0, 0));
      put.add(FAMILY_INFO, COLUMN_PREFIX_LENGTH, Bytes.toBytes(2));
      put.add(FAMILY_INFO, COLUMN_BUCKET_SIZE, Bytes.toBytes(0L));
      indexTable.put(put);
    } else {
      indexTable = new HTable(config, indexTableName);
    }

    this.splitThreshold = splitThreshold;

    this.splitTimes = 0;
  }

  /**
   * fetches a bucket which holds the queried row.
   * 
   * @param row
   *          a queried row key
   * @return a bucket which holds the queried row.
   * @throws IOException
   */
  public Bucket fetchBucket(byte[] row) throws IOException {
    Result bucketEntry = indexTable.getRowOrBefore(row, FAMILY_INFO);
    byte[] bucketKey = bucketEntry.getRow();
    int prefixLength = Bytes.toInt(bucketEntry.getValue(FAMILY_INFO,
        COLUMN_PREFIX_LENGTH));
    Range[] ranges = toRanges(bucketKey, prefixLength);
    return createBucket(ranges);
  }

  private Range[] toRanges(byte[] bucketKey, int prefixLength) {
    byte[] suffix_ones = Utils.not(Utils.makeMask(prefixLength));
    // substitute don't cares to 0s. ex. [010*****] -> [01000000]
    int[] mins = Utils.bitwiseUnzip(bucketKey);
    // substitute don't cares to 1s. ex. [010*****] -> [01011111]
    int[] maxs = Utils.bitwiseUnzip(Utils.or(bucketKey, suffix_ones));
    Range[] ranges = new Range[2];
    ranges[0] = new Range(mins[0], maxs[0]);
    ranges[1] = new Range(mins[1], maxs[1]);
    return ranges;
  }

  /**
   * finds buckets which intersect with the query region.
   * 
   * @param rx
   * @param ry
   * @return
   * @throws IOException
   */
  public Iterable<Bucket> findBucketsInRange(Range rx, Range ry)
      throws IOException {
    byte[] probeKey = Utils.bitwiseZip(rx.min, ry.min);
    Result bucketEntry = indexTable.getRowOrBefore(probeKey, FAMILY_INFO);
    byte[] startKey = bucketEntry.getRow();
    byte[] stopKey = Bytes.incrementBytes(Utils.bitwiseZip(rx.max, ry.max), 1L);
    Scan scan = new Scan(startKey, stopKey);
    scan.addFamily(FAMILY_INFO);
    scan.setCaching(1000);
    ResultScanner results = indexTable.getScanner(scan);
    List<Bucket> hitBuckets = new LinkedList<Bucket>();
    for (Result result : results) {
      byte[] row = result.getRow();
      int pl = Bytes.toInt(result.getValue(FAMILY_INFO, COLUMN_PREFIX_LENGTH));
      Range[] rs = toRanges(row, pl);
      if (rx.intersect(rs[0]) && ry.intersect(rs[1])) {
        hitBuckets.add(createBucket(rs));
      }
    }
    return hitBuckets;
  }

  private Bucket createBucket(Range[] rs) {
    return new Bucket(dataTable, rs[0], rs[1], this);
  }

  /**
   * 
   * @param row
   * @throws IOException
   */
  void notifyInsertion(byte[] row) throws IOException {
    Result bucketEntry = indexTable.getRowOrBefore(row, FAMILY_INFO);
    byte[] bucketKey = bucketEntry.getRow();
    int prefixLength = Bytes.toInt(bucketEntry.getValue(FAMILY_INFO,
            COLUMN_PREFIX_LENGTH));
    long size = indexTable.incrementColumnValue(bucketKey, FAMILY_INFO,
        COLUMN_BUCKET_SIZE, 1L);
    maySplit(bucketKey,prefixLength, size);
  }

  private void maySplit(byte[] bucketKey,int prefixLength, long size) throws IOException {
    if (size > splitThreshold) {
      splitTimes+=1;
      splitBucket(bucketKey,prefixLength,size);
    }
  }

  /*
   * bucket [abc*****] is partitioned into bucket [abc0****] and bucket
   * [abc1****].
   */
  private void splitBucket(byte[] bucketKey,int prefixLength,long bucketSize) throws IOException {
//    Result bucketEntry = indexTable.getRowOrBefore(splitKey, FAMILY_INFO);
//    byte[] bucketKey = bucketEntry.getRow();
//    int prefixLength = Bytes.toInt(bucketEntry.getValue(FAMILY_INFO,
//        COLUMN_PREFIX_LENGTH));
//    long bucketSize = Bytes.toLong(bucketEntry.getValue(FAMILY_INFO,
//        COLUMN_BUCKET_SIZE));
    int newPrefixLength = prefixLength + 1;
    if (newPrefixLength > 32 * 2) {
      return; // exceeds the maximum prefix length.
    }

    byte[] newChildKey0 = bucketKey;
    byte[] newChildKey1 = Utils.makeBit(bucketKey, prefixLength);
    Scan scan = new Scan(newChildKey0, newChildKey1);
    scan.addFamily(Bucket.FAMILY);
    scan.setCaching(10000);
    ResultScanner results = dataTable.getScanner(scan);
    long newSize = 0L;
    for (Result result : results) {
      newSize += result.getFamilyMap(Bucket.FAMILY).size();
//      System.out.println(result.getFamilyMap(Bucket.FAMILY));
    }

    Put put0 = new Put(newChildKey0);
    put0.add(FAMILY_INFO, COLUMN_PREFIX_LENGTH, Bytes.toBytes(newPrefixLength));
    put0.add(FAMILY_INFO, COLUMN_BUCKET_SIZE, Bytes.toBytes(newSize));
    Put put1 = new Put(newChildKey1);
    put1.add(FAMILY_INFO, COLUMN_PREFIX_LENGTH, Bytes.toBytes(newPrefixLength));
    put1.add(FAMILY_INFO, COLUMN_BUCKET_SIZE,
        Bytes.toBytes(bucketSize - newSize));
    List<Put> puts = new ArrayList<Put>(2);
    puts.add(put0);
    puts.add(put1);
    indexTable.put(puts);
    maySplit(newChildKey0,newPrefixLength, newSize);
    maySplit(newChildKey1,newPrefixLength,(bucketSize - newSize));
  }

  public int getSplitTimes(){
    return splitTimes;
  }

  public HTable getDataTable(){
    return dataTable;
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.io.Closeable#close()
   */
  @Override
  public void close() throws IOException {
    Closeables.closeQuietly(dataTable);
    Closeables.closeQuietly(indexTable);
  }
}

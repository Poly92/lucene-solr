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
package org.apache.solr.cloud;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;
import org.apache.solr.client.solrj.cloud.autoscaling.Policy;
import org.apache.solr.client.solrj.cloud.autoscaling.PolicyHelper;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.SolrClientDataProvider;
import org.apache.solr.client.solrj.impl.ZkClientClusterStateProvider;
import org.apache.solr.cloud.rule.ReplicaAssigner;
import org.apache.solr.cloud.rule.Rule;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ReplicaPosition;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.zookeeper.KeeperException;

import static java.util.Collections.singletonMap;
import static org.apache.solr.client.solrj.cloud.autoscaling.Policy.POLICY;
import static org.apache.solr.cloud.OverseerCollectionMessageHandler.CREATE_NODE_SET;
import static org.apache.solr.cloud.OverseerCollectionMessageHandler.CREATE_NODE_SET_EMPTY;
import static org.apache.solr.cloud.OverseerCollectionMessageHandler.CREATE_NODE_SET_SHUFFLE;
import static org.apache.solr.cloud.OverseerCollectionMessageHandler.CREATE_NODE_SET_SHUFFLE_DEFAULT;
import static org.apache.solr.common.cloud.DocCollection.SNITCH;
import static org.apache.solr.common.cloud.ZkStateReader.CORE_NAME_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.MAX_SHARDS_PER_NODE;
import static org.apache.solr.common.cloud.ZkStateReader.SOLR_AUTOSCALING_CONF_PATH;


public class Assign {
  private static Pattern COUNT = Pattern.compile("core_node(\\d+)");

  public static String assignNode(DocCollection collection) {
    Map<String, Slice> sliceMap = collection != null ? collection.getSlicesMap() : null;
    if (sliceMap == null) {
      return "core_node1";
    }

    int max = 0;
    for (Slice slice : sliceMap.values()) {
      for (Replica replica : slice.getReplicas()) {
        Matcher m = COUNT.matcher(replica.getName());
        if (m.matches()) {
          max = Math.max(max, Integer.parseInt(m.group(1)));
        }
      }
    }

    return "core_node" + (max + 1);
  }

  /**
   * Assign a new unique id up to slices count - then add replicas evenly.
   *
   * @return the assigned shard id
   */
  public static String assignShard(DocCollection collection, Integer numShards) {
    if (numShards == null) {
      numShards = 1;
    }
    String returnShardId = null;
    Map<String, Slice> sliceMap = collection != null ? collection.getActiveSlicesMap() : null;


    // TODO: now that we create shards ahead of time, is this code needed?  Esp since hash ranges aren't assigned when creating via this method?

    if (sliceMap == null) {
      return "shard1";
    }

    List<String> shardIdNames = new ArrayList<>(sliceMap.keySet());

    if (shardIdNames.size() < numShards) {
      return "shard" + (shardIdNames.size() + 1);
    }

    // TODO: don't need to sort to find shard with fewest replicas!

    // else figure out which shard needs more replicas
    final Map<String, Integer> map = new HashMap<>();
    for (String shardId : shardIdNames) {
      int cnt = sliceMap.get(shardId).getReplicasMap().size();
      map.put(shardId, cnt);
    }

    Collections.sort(shardIdNames, (String o1, String o2) -> {
      Integer one = map.get(o1);
      Integer two = map.get(o2);
      return one.compareTo(two);
    });

    returnShardId = shardIdNames.get(0);
    return returnShardId;
  }

  public static String buildCoreName(String collectionName, String shard, Replica.Type type, int replicaNum) {
    // TODO: Adding the suffix is great for debugging, but may be an issue if at some point we want to support a way to change replica type
    return String.format(Locale.ROOT, "%s_%s_replica_%s%s", collectionName, shard, type.name().substring(0,1).toLowerCase(Locale.ROOT), replicaNum);
  }

  public static String buildCoreName(DocCollection collection, String shard, Replica.Type type) {
    Slice slice = collection.getSlice(shard);
    int replicaNum = slice.getReplicas().size();
    for (; ; ) {
      String replicaName = buildCoreName(collection.getName(), shard, type, replicaNum);
      boolean exists = false;
      for (Replica replica : slice.getReplicas()) {
        if (replicaName.equals(replica.getStr(CORE_NAME_PROP))) {
          exists = true;
          break;
        }
      }
      if (exists) replicaNum++;
      else return replicaName;
    }
  }
  public static List<String> getLiveOrLiveAndCreateNodeSetList(final Set<String> liveNodes, final ZkNodeProps message, final Random random) {
    // TODO: add smarter options that look at the current number of cores per
    // node?
    // for now we just go random (except when createNodeSet and createNodeSet.shuffle=false are passed in)

    List<String> nodeList;

    final String createNodeSetStr = message.getStr(CREATE_NODE_SET);
    final List<String> createNodeList = (createNodeSetStr == null) ? null : StrUtils.splitSmart((CREATE_NODE_SET_EMPTY.equals(createNodeSetStr) ? "" : createNodeSetStr), ",", true);

    if (createNodeList != null) {
      nodeList = new ArrayList<>(createNodeList);
      nodeList.retainAll(liveNodes);
      if (message.getBool(CREATE_NODE_SET_SHUFFLE, CREATE_NODE_SET_SHUFFLE_DEFAULT)) {
        Collections.shuffle(nodeList, random);
      }
    } else {
      nodeList = new ArrayList<>(liveNodes);
      Collections.shuffle(nodeList, random);
    }

    return nodeList;
  }

  public static List<ReplicaPosition> identifyNodes(Supplier<CoreContainer> coreContainer,
                                                    ZkStateReader zkStateReader,
                                                    ClusterState clusterState,
                                                    List<String> nodeList,
                                                    String collectionName,
                                                    ZkNodeProps message,
                                                    List<String> shardNames,
                                                    int numNrtReplicas,
                                                    int numTlogReplicas,
                                                    int numPullReplicas) throws KeeperException, InterruptedException {
    List<Map> rulesMap = (List) message.get("rule");
    String policyName = message.getStr(POLICY);
    Map autoScalingJson = Utils.getJson(zkStateReader.getZkClient(), SOLR_AUTOSCALING_CONF_PATH, true);

    if (rulesMap == null && policyName == null) {
      int i = 0;
      List<ReplicaPosition> result = new ArrayList<>();
      for (String aShard : shardNames) {

        for (Map.Entry<Replica.Type, Integer> e : ImmutableMap.of(Replica.Type.NRT, numNrtReplicas,
            Replica.Type.TLOG, numTlogReplicas,
            Replica.Type.PULL, numPullReplicas
        ).entrySet()) {
          for (int j = 0; j < e.getValue(); j++){
            result.add(new ReplicaPosition(aShard, j, e.getKey(), nodeList.get(i % nodeList.size())));
            i++;
          }
        }

      }
      return result;
    } else {
      if (numTlogReplicas + numPullReplicas != 0) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            Replica.Type.TLOG + " or " + Replica.Type.PULL + " replica types not supported with placement rules or cluster policies");
      }
    }

    if (policyName != null || autoScalingJson.get(Policy.CLUSTER_POLICY) != null) {
      return getPositionsUsingPolicy(collectionName,
          shardNames, numNrtReplicas, policyName, zkStateReader, nodeList);
    } else {
      List<Rule> rules = new ArrayList<>();
      for (Object map : rulesMap) rules.add(new Rule((Map) map));
      Map<String, Integer> sharVsReplicaCount = new HashMap<>();

      for (String shard : shardNames) sharVsReplicaCount.put(shard, numNrtReplicas);
      ReplicaAssigner replicaAssigner = new ReplicaAssigner(rules,
          sharVsReplicaCount,
          (List<Map>) message.get(SNITCH),
          new HashMap<>(),//this is a new collection. So, there are no nodes in any shard
          nodeList,
          coreContainer.get(),
          clusterState);

      Map<ReplicaPosition, String> nodeMappings = replicaAssigner.getNodeMappings();
      return nodeMappings.entrySet().stream()
          .map(e -> new ReplicaPosition(e.getKey().shard, e.getKey().index, e.getKey().type, e.getValue()))
          .collect(Collectors.toList());
    }
  }

  static class ReplicaCount {
    public final String nodeName;
    public int thisCollectionNodes = 0;
    public int totalNodes = 0;

    ReplicaCount(String nodeName) {
      this.nodeName = nodeName;
    }

    public int weight() {
      return (thisCollectionNodes * 100) + totalNodes;
    }
  }

  // Only called from createShard and addReplica (so far).
  //
  // Gets a list of candidate nodes to put the required replica(s) on. Throws errors if not enough replicas
  // could be created on live nodes given maxShardsPerNode, Replication factor (if from createShard) etc.
  public static List<ReplicaCount> getNodesForNewReplicas(ClusterState clusterState, String collectionName,
                                                          String shard, int numberOfNodes,
                                                          Object createNodeSet, CoreContainer cc) throws KeeperException, InterruptedException {
    DocCollection coll = clusterState.getCollection(collectionName);
    Integer maxShardsPerNode = coll.getInt(MAX_SHARDS_PER_NODE, 1);
    List<String> createNodeList = null;

    if (createNodeSet instanceof List) {
      createNodeList = (List) createNodeSet;
    } else {
      createNodeList = createNodeSet == null ? null : StrUtils.splitSmart((String) createNodeSet, ",", true);
    }

     HashMap<String, ReplicaCount> nodeNameVsShardCount = getNodeNameVsShardCount(collectionName, clusterState, createNodeList);

    if (createNodeList == null) { // We only care if we haven't been told to put new replicas on specific nodes.
      int availableSlots = 0;
      for (Map.Entry<String, ReplicaCount> ent : nodeNameVsShardCount.entrySet()) {
        //ADDREPLICA can put more than maxShardsPerNode on an instance, so this test is necessary.
        if (maxShardsPerNode > ent.getValue().thisCollectionNodes) {
          availableSlots += (maxShardsPerNode - ent.getValue().thisCollectionNodes);
        }
      }
      if (availableSlots < numberOfNodes) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            String.format(Locale.ROOT, "Cannot create %d new replicas for collection %s given the current number of live nodes and a maxShardsPerNode of %d",
                numberOfNodes, collectionName, maxShardsPerNode));
      }
    }

    List l = (List) coll.get(DocCollection.RULE);
    List<ReplicaPosition> replicaPositions = null;
    if (l != null) {
      replicaPositions = getNodesViaRules(clusterState, shard, numberOfNodes, cc, coll, createNodeList, l);
    }
    String policyName = coll.getStr(POLICY);
    Map autoScalingJson = Utils.getJson(cc.getZkController().getZkClient(), SOLR_AUTOSCALING_CONF_PATH, true);
    if (policyName != null || autoScalingJson.get(Policy.CLUSTER_POLICY) != null) {
      replicaPositions = Assign.getPositionsUsingPolicy(collectionName, Collections.singletonList(shard), numberOfNodes,
          policyName, cc.getZkController().getZkStateReader(), createNodeList);
    }

    if(replicaPositions != null){
      List<ReplicaCount> repCounts = new ArrayList<>();
      for (ReplicaPosition p : replicaPositions) {
        repCounts.add(new ReplicaCount(p.node));
      }
      return repCounts;
    }

    ArrayList<ReplicaCount> sortedNodeList = new ArrayList<>(nodeNameVsShardCount.values());
    Collections.sort(sortedNodeList, (x, y) -> (x.weight() < y.weight()) ? -1 : ((x.weight() == y.weight()) ? 0 : 1));
    return sortedNodeList;

  }

  public static List<ReplicaPosition> getPositionsUsingPolicy(String collName, List<String> shardNames, int numReplicas,
                                                              String policyName, ZkStateReader zkStateReader,
                                                              List<String> nodesList) throws KeeperException, InterruptedException {
    try (CloudSolrClient csc = new CloudSolrClient.Builder()
        .withClusterStateProvider(new ZkClientClusterStateProvider(zkStateReader))
        .build()) {
      SolrClientDataProvider clientDataProvider = new SolrClientDataProvider(csc);
      Map<String, Object> autoScalingJson = Utils.getJson(zkStateReader.getZkClient(), SOLR_AUTOSCALING_CONF_PATH, true);
      Map<String, List<String>> locations = PolicyHelper.getReplicaLocations(collName,
          autoScalingJson,
          clientDataProvider, singletonMap(collName, policyName), shardNames, numReplicas, nodesList);
      List<ReplicaPosition> result = new ArrayList<>();
      for (Map.Entry<String, List<String>> e : locations.entrySet()) {
        List<String> value = e.getValue();
        for (int i = 0; i < value.size(); i++) {
          result.add(new ReplicaPosition(e.getKey(), i, Replica.Type.NRT, value.get(i)));
        }
      }
      return result;
    } catch (IOException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error closing CloudSolrClient",e);
    }
  }

  private static List<ReplicaPosition> getNodesViaRules(ClusterState clusterState, String shard, int numberOfNodes,
                                                        CoreContainer cc, DocCollection coll, List<String> createNodeList, List l) {
    ArrayList<Rule> rules = new ArrayList<>();
    for (Object o : l) rules.add(new Rule((Map) o));
    Map<String, Map<String, Integer>> shardVsNodes = new LinkedHashMap<>();
    for (Slice slice : coll.getSlices()) {
      LinkedHashMap<String, Integer> n = new LinkedHashMap<>();
      shardVsNodes.put(slice.getName(), n);
      for (Replica replica : slice.getReplicas()) {
        Integer count = n.get(replica.getNodeName());
        if (count == null) count = 0;
        n.put(replica.getNodeName(), ++count);
      }
    }
    List snitches = (List) coll.get(SNITCH);
    List<String> nodesList = createNodeList == null ?
        new ArrayList<>(clusterState.getLiveNodes()) :
        createNodeList;
    Map<ReplicaPosition, String> positions = new ReplicaAssigner(
        rules,
        Collections.singletonMap(shard, numberOfNodes),
        snitches,
        shardVsNodes,
        nodesList, cc, clusterState).getNodeMappings();

    return positions.entrySet().stream().map(e -> e.getKey().setNode(e.getValue())).collect(Collectors.toList());// getReplicaCounts(positions);
  }

  private static HashMap<String, ReplicaCount> getNodeNameVsShardCount(String collectionName,
                                                                       ClusterState clusterState, List<String> createNodeList) {
    Set<String> nodes = clusterState.getLiveNodes();

    List<String> nodeList = new ArrayList<>(nodes.size());
    nodeList.addAll(nodes);
    if (createNodeList != null) nodeList.retainAll(createNodeList);

    HashMap<String, ReplicaCount> nodeNameVsShardCount = new HashMap<>();
    for (String s : nodeList) {
      nodeNameVsShardCount.put(s, new ReplicaCount(s));
    }
    if (createNodeList != null) { // Overrides petty considerations about maxShardsPerNode
      if (createNodeList.size() != nodeNameVsShardCount.size()) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            "At least one of the node(s) specified are not currently active, no action taken.");
      }
      return nodeNameVsShardCount;
    }
    DocCollection coll = clusterState.getCollection(collectionName);
    Integer maxShardsPerNode = coll.getInt(MAX_SHARDS_PER_NODE, 1);
    Map<String, DocCollection> collections = clusterState.getCollectionsMap();
    for (Map.Entry<String, DocCollection> entry : collections.entrySet()) {
      DocCollection c = entry.getValue();
      //identify suitable nodes  by checking the no:of cores in each of them
      for (Slice slice : c.getSlices()) {
        Collection<Replica> replicas = slice.getReplicas();
        for (Replica replica : replicas) {
          ReplicaCount count = nodeNameVsShardCount.get(replica.getNodeName());
          if (count != null) {
            count.totalNodes++; // Used ot "weigh" whether this node should be used later.
            if (entry.getKey().equals(collectionName)) {
              count.thisCollectionNodes++;
              if (count.thisCollectionNodes >= maxShardsPerNode) nodeNameVsShardCount.remove(replica.getNodeName());
            }
          }
        }
      }
    }

    return nodeNameVsShardCount;
  }


}

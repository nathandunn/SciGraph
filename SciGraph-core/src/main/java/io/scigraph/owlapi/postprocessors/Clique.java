/**
 * Copyright (C) 2014 The SciGraph authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.scigraph.owlapi.postprocessors;

import io.scigraph.frames.NodeProperties;
import io.scigraph.neo4j.GraphUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Uniqueness;
import org.neo4j.tooling.GlobalGraphOperations;

import com.google.common.base.Optional;
import com.google.common.collect.Iterators;

public class Clique implements Postprocessor {
  private static final Logger logger = Logger.getLogger(CliqueDrei.class.getName());


  static final String ORIGINAL_REFERENCE_KEY_SOURCE = "equivalentOriginalNodeSource";
  static final String ORIGINAL_REFERENCE_KEY_TARGET = "equivalentOriginalNodeTarget";
  static final String CLIQUE_LEADER_PROPERTY = "cliqueLeader";
  static final Label CLIQUE_LEADER_LABEL = DynamicLabel.label(CLIQUE_LEADER_PROPERTY);

  private List<String> prefixLeaderPriority;
  private String leaderAnnotationProperty;
  private Set<Label> forbiddenLabels;
  private Set<RelationshipType> relationships;

  private final GraphDatabaseService graphDb;

  @Inject
  public Clique(GraphDatabaseService graphDb, CliqueConfiguration cliqueConfiguration) {
    this.graphDb = graphDb;
    this.prefixLeaderPriority = cliqueConfiguration.getLeaderPriority();
    this.leaderAnnotationProperty = cliqueConfiguration.getLeaderAnnotation();

    Set<Label> tmpLabels = new HashSet<Label>();
    for (String l : cliqueConfiguration.getLeaderForbiddenLabels()) {
      tmpLabels.add(DynamicLabel.label(l));
    }
    this.forbiddenLabels = tmpLabels;

    Set<RelationshipType> tmpRelationships = new HashSet<RelationshipType>();
    for (String r : cliqueConfiguration.getRelationships()) {
      tmpRelationships.add(DynamicRelationshipType.withName(r));
    }
    this.relationships = tmpRelationships;
  }

  @Override
  public void run() {
    logger.info("Starting clique merge");
    GlobalGraphOperations globalGraphOperations = GlobalGraphOperations.at(graphDb);

    Transaction tx = graphDb.beginTx();
    ResourceIterable<Node> allNodes = globalGraphOperations.getAllNodes();
    int size = Iterators.size(allNodes.iterator());
    tx.success();
    tx.close();

    logger.info(size + " nodes left to process");

    tx = graphDb.beginTx();
    TraversalDescription traversalDescription = graphDb.traversalDescription().uniqueness(Uniqueness.NODE_GLOBAL);
    for (RelationshipType rel : relationships) {
      traversalDescription = traversalDescription.relationships(rel, Direction.BOTH);
    }

    Set<Long> processedNodes = new HashSet<Long>();

    for (Node baseNode : allNodes) {

      size -= 1;

      if (size % 100000 == 0) {
        logger.info(size + " nodes left to process");
      }
      
      if (size % 10 == 0) {
        tx.success();
        tx.close();
        tx = graphDb.beginTx();
      }

      logger.fine("Processing Node - " + baseNode.getProperty(NodeProperties.IRI));

      if (!processedNodes.contains(baseNode.getId())) {
        // Keep a list of equivalentNodes
        List<Node> clique = new ArrayList<Node>();
        for (Node node : traversalDescription.traverse(baseNode).nodes()) {
          clique.add(node);
          processedNodes.add(node.getId());
        }
        
        if (clique.size() == 1) {
          Node defactoLeader = clique.get(0);
          markAsCliqueLeader(defactoLeader);
          markLeaderEdges(defactoLeader);
        } else {
          Node leader = electCliqueLeader(clique, prefixLeaderPriority);
          markAsCliqueLeader(leader);
          clique.remove(leader); // keep only the peasants
          markLeaderEdges(leader);
          ensureLabel(leader, clique);
          moveEdgesToLeader(leader, clique);
        }
        
      }
      
    }

    tx.success();
    tx.close();
  }

  private void moveRelationship(Node from, Node to, Relationship rel, String property) {
    Relationship newRel = null;
    if (property == ORIGINAL_REFERENCE_KEY_TARGET) {
      newRel = rel.getOtherNode(from).createRelationshipTo(to, rel.getType());
    } else {
      newRel = to.createRelationshipTo(rel.getOtherNode(from), rel.getType());
    }
    copyProperties(rel, newRel);
    rel.delete();
    newRel.setProperty(property, from.getProperty(NodeProperties.IRI));
  }

  private void copyProperties(PropertyContainer source, PropertyContainer target) {
    for (String key : source.getPropertyKeys())
      target.setProperty(key, source.getProperty(key));
  }

  private boolean isOneOfType(Relationship r, Set<RelationshipType> relationships) {
    for (RelationshipType rel : relationships) {
      if (r.isType(rel)) {
        return true;
      }
    }
    return false;
  }

  private void moveEdgesToLeader(Node leader, List<Node> clique) {
    for (Node n : clique) {
      logger.fine("Processing underNode - " + n.getProperty(NodeProperties.IRI));
      Iterable<Relationship> rels = n.getRelationships();
      for (Relationship rel : rels) {
        if ((isOneOfType(rel, relationships)) && (rel.getStartNode().getId() == leader.getId() || rel.getEndNode().getId() == leader.getId())) {
          logger.fine("equivalence relation which is already attached to the leader, do nothing");
        } else {
          if ((rel.getEndNode().getId() == n.getId())) {
            logger.fine("MOVE TARGET " + rel.getType() + " FROM " + n.getProperty(NodeProperties.IRI) + " TO "
                + leader.getProperty(NodeProperties.IRI));

            moveRelationship(n, leader, rel, ORIGINAL_REFERENCE_KEY_TARGET);
          } else if ((rel.getStartNode().getId() == n.getId())) {
            logger.fine("MOVE SOURCE " + rel.getType() + " FROM " + n.getProperty(NodeProperties.IRI) + " TO "
                + leader.getProperty(NodeProperties.IRI));

            moveRelationship(n, leader, rel, ORIGINAL_REFERENCE_KEY_SOURCE);
          }
        }
      }
    }
  }

  // TODO that's hacky
  private void ensureLabel(Node leader, List<Node> clique) {
    // Move rdfs:label if non-existing on leader
    if (!leader.hasProperty(NodeProperties.LABEL)) {
      for (Node n : clique) {
        if (n.hasProperty(NodeProperties.LABEL) && n.hasProperty("http://www.w3.org/2000/01/rdf-schema#label")) {
          leader.setProperty(NodeProperties.LABEL, n.getProperty(NodeProperties.LABEL));
          leader.setProperty("http://www.w3.org/2000/01/rdf-schema#label", n.getProperty("http://www.w3.org/2000/01/rdf-schema#label"));
          return;
        }
      }
    }
  }

  private void markLeaderEdges(Node leader) {
    for (Relationship r : leader.getRelationships()) {
      if (r.getStartNode().getId() == leader.getId()) {
        r.setProperty(ORIGINAL_REFERENCE_KEY_SOURCE, CLIQUE_LEADER_PROPERTY);
      } else {
        r.setProperty(ORIGINAL_REFERENCE_KEY_TARGET, CLIQUE_LEADER_PROPERTY);
      }
    }
  }

  private void markAsCliqueLeader(Node n) {
    if (!n.hasLabel(CLIQUE_LEADER_LABEL)) {
      n.addLabel(CLIQUE_LEADER_LABEL);
    }
  }

  public Node electCliqueLeader(List<Node> clique, List<String> prefixLeaderPriority) {
    List<Node> designatedLeaders = designatedLeader(clique);
    if (designatedLeaders.size() > 1) {
      logger.severe("More than one node in a clique designated as leader. Using failover strategy to elect a leader.");
      for (Node n : designatedLeaders) {
        logger.severe(n.getProperty(NodeProperties.IRI).toString());
      }
      return filterByPrefix(designatedLeaders, prefixLeaderPriority);
    } else if (designatedLeaders.size() == 1) {
      return designatedLeaders.get(0);
    } else {
      return filterByPrefix(clique, prefixLeaderPriority);
    }
  }

  private List<Node> designatedLeader(List<Node> clique) {
    List<Node> designatedNodes = new ArrayList<Node>();
    for (Node n : clique) {
      for (String k : n.getPropertyKeys()) {
        if (leaderAnnotationProperty.equals(k)) {
          designatedNodes.add(n);
        }
      }
    }
    return designatedNodes;
  }

  private Node filterByPrefix(List<Node> clique, List<String> leaderPriorityIri) {
    List<Node> filteredByPrefix = new ArrayList<Node>();
    if (!leaderPriorityIri.isEmpty()) {
      String iriPriority = leaderPriorityIri.get(0);
      for (Node n : clique) {
        Optional<String> iri = GraphUtil.getProperty(n, NodeProperties.IRI, String.class);
        if (iri.isPresent() && iri.get().contains(iriPriority)) {
          filteredByPrefix.add(n);
        }
      }
      if (filteredByPrefix.isEmpty()) {
        filterByPrefix(clique, leaderPriorityIri.subList(1, leaderPriorityIri.size()));
      }
    }

    if (filteredByPrefix.isEmpty()) {
      filteredByPrefix = clique;
    }
    Collections.sort(filteredByPrefix, new Comparator<Node>() {
      @Override
      public int compare(Node node1, Node node2) {
        Optional<String> iri1 = GraphUtil.getProperty(node1, NodeProperties.IRI, String.class);
        Optional<String> iri2 = GraphUtil.getProperty(node2, NodeProperties.IRI, String.class);
        return iri1.get().compareTo(iri2.get());
      }
    });

    List<Node> filteredByPrefixAndForbiddenLabels = new ArrayList<Node>();
    for (Node n : filteredByPrefix) {
      if (!containsOneLabel(n, forbiddenLabels)) {
        filteredByPrefixAndForbiddenLabels.add(n);
      }
    }

    if (filteredByPrefixAndForbiddenLabels.isEmpty()) {
      return filteredByPrefix.get(0);
    } else {
      return filteredByPrefixAndForbiddenLabels.get(0);
    }
  }

  private boolean containsOneLabel(Node n, Set<Label> labels) {
    for (Label l : labels) {
      if (n.hasLabel(l)) {
        return true;
      }
    }
    return false;
  }

}

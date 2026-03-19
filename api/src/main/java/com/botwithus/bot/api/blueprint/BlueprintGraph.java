package com.botwithus.bot.api.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full blueprint graph containing nodes, links, variables, and metadata.
 */
public class BlueprintGraph {

    private BlueprintMetadata metadata;
    private final List<NodeInstance> nodes = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private final Map<String, PinType> variables = new LinkedHashMap<>();
    private long nextId = 1;

    public BlueprintGraph() {
        this.metadata = new BlueprintMetadata("Untitled", "1.0", "", "");
    }

    public BlueprintGraph(BlueprintMetadata metadata) {
        this.metadata = metadata;
    }

    public long allocateId() {
        return nextId++;
    }

    public BlueprintMetadata getMetadata() { return metadata; }
    public void setMetadata(BlueprintMetadata metadata) { this.metadata = metadata; }

    public List<NodeInstance> getNodes() { return nodes; }
    public List<Link> getLinks() { return links; }
    public Map<String, PinType> getVariables() { return variables; }

    public long getNextId() { return nextId; }
    public void setNextId(long nextId) { this.nextId = nextId; }

    public NodeInstance addNode(String typeId, float x, float y) {
        NodeInstance node = new NodeInstance(allocateId(), typeId, x, y);
        nodes.add(node);
        return node;
    }

    public Link addLink(long sourcePinId, long targetPinId) {
        Link link = new Link(allocateId(), sourcePinId, targetPinId);
        links.add(link);
        return link;
    }

    public void removeNode(long nodeId) {
        nodes.removeIf(n -> n.getId() == nodeId);
        // Remove links connected to any pin of this node
        links.removeIf(l -> l.sourcePinId() / 1000 == nodeId || l.targetPinId() / 1000 == nodeId);
    }

    public void removeLink(long linkId) {
        links.removeIf(l -> l.id() == linkId);
    }

    public NodeInstance findNode(long nodeId) {
        for (NodeInstance node : nodes) {
            if (node.getId() == nodeId) return node;
        }
        return null;
    }

    /**
     * Finds the link whose target is the given pin ID.
     */
    public Link findLinkToPin(long pinId) {
        for (Link link : links) {
            if (link.targetPinId() == pinId) return link;
        }
        return null;
    }

    /**
     * Finds all links whose source is the given pin ID.
     */
    public List<Link> findLinksFromPin(long pinId) {
        List<Link> result = new ArrayList<>();
        for (Link link : links) {
            if (link.sourcePinId() == pinId) result.add(link);
        }
        return result;
    }
}

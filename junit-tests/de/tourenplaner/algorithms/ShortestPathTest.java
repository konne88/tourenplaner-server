/*
 * Copyright 2012 ToureNPlaner
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package de.tourenplaner.algorithms;

import de.tourenplaner.computecore.RequestPoint;
import de.tourenplaner.computecore.RequestPoints;
import de.tourenplaner.computecore.Way;
import de.tourenplaner.graphrep.GraphRep;
import de.tourenplaner.graphrep.TestGraphReader;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * @author Christoph Haag, Sascha Meusel, Niklas Schnelle, Peter Vollmer
 */
public class ShortestPathTest {

    private Random random = new Random(1337);
    private int nodeCount = 0;
    private GraphRep graph = null;
    private ShortestPath shortestPath = null;
    private ShortestPathCH shortestPathCH = null;

    private int testCaseNumber = 0;
    
    private void runTestCase(int n, boolean tour) throws ComputeException, IllegalAccessException {
        RequestPoints points = new RequestPoints();
        for (int i=0; i<n; i++) {
            int randomId = random.nextInt(nodeCount);
            points.addPoint(graph.getNodeLat(randomId),graph.getNodeLon(randomId));
        }
        if (tour) {
            points.addPoint(points.getPointLat(0), points.getPointLon(0));
        }
        // TODO if we do not add here constraints hash map, the test fails. Maybe bad behavior of ShortestPath
        for (RequestPoint point : points.getStore()) {
            point.setConstraints(new HashMap<String, Object>());
        }

        testCaseNumber++;
        List<Way> wayList = new ArrayList<Way>();
        points.setIdsFromGraph(graph);

        int totalDist;
        if (shortestPath != null) {
            totalDist = shortestPath.shortestPath(points, wayList, false);
        } else {
            totalDist = shortestPathCH.shortestPath(points, wayList, false);
        }
        
        int expectedTotalDist = 0;
        
        // pointNumber is the number of the start points within points 
        for (int pointNumber=0; pointNumber < points.size()-1; pointNumber++) {

            int[] dist = new int[nodeCount];
            for (int nodeId = 0; nodeId < nodeCount; nodeId++) {
                dist[nodeId] = Integer.MAX_VALUE;
            }
            dist[points.getPointId(pointNumber)] = 0;
            List<Integer> queue = new ArrayList<Integer>();
            queue.add(points.getPointId(pointNumber));
            while (queue.size() > 0) {
                int currentNode = queue.remove(0);
                for (int edge=0; edge < graph.getOutEdgeCount(currentNode); edge++) {
                    int tempEdgeId = graph.getOutEdgeId(currentNode, edge);
                    int targetId = graph.getTarget(tempEdgeId);
                    int tempDist = dist[currentNode] + graph.getDist(tempEdgeId);
                    if (tempDist < dist[targetId] && tempDist <= totalDist) {
                        dist[targetId] = tempDist;
                        queue.add(targetId);
                    }
                }
            }
            expectedTotalDist = expectedTotalDist + dist[points.getPointId(pointNumber + 1)];
        }
        
        assertEquals("The distance of test case " + testCaseNumber + " seems wrong.", expectedTotalDist,totalDist);
        System.out.println("Test case " + testCaseNumber + " finished.");
    }


    private void prepareTestRun() {
        graph = (new TestGraphReader()).readTestGraph();
        nodeCount = graph.getNodeCount();
    }

    private void runTestCases() throws ComputeException, IllegalAccessException {
        runTestCase(2, false);
        runTestCase(2, true);
        runTestCase(2, false);
        runTestCase(2, true);
        runTestCase(2, false);
        runTestCase(2, true);

        runTestCase(3, false);
        runTestCase(3, true);
        runTestCase(3, false);
        runTestCase(3, true);
        runTestCase(3, false);
        runTestCase(3, true);

        runTestCase(4, false);
        runTestCase(4, true);
        runTestCase(4, false);
        runTestCase(4, true);
        runTestCase(4, false);
        runTestCase(4, true);
    }

    @Test
    public void testShortestPath() {
        prepareTestRun();
        GraphAlgorithmFactory fac = new ShortestPathFactory(graph);
        shortestPath = (ShortestPath) fac.createAlgorithm();
        shortestPathCH = null;

        try {
            runTestCases();
        } catch (Exception e) {
            fail("Failed with exception: " + e.getMessage());
        }
    }

    @Test
    public void testShortestPathCH() {
        prepareTestRun();
        GraphAlgorithmFactory fac = new ShortestPathCHFactory(graph);
        shortestPath = null;
        shortestPathCH = (ShortestPathCH) fac.createAlgorithm();

        try {
            runTestCases();
        } catch (Exception e) {
            fail("Failed with exception: " + e.getMessage());
        }
    }
}
                            
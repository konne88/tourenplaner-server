/**
 * $$\\ToureNPlaner\\$$
 */
package algorithms;

import graphrep.GraphRep;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Niklas Schnelle, Peter Vollmer
 * 
 */
public class ShortestPathFactory extends GraphAlgorithmFactory {

	public ShortestPathFactory(GraphRep graph) {
		super(graph);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see algorithms.AlgorithmFactory#createAlgorithm()
	 */
	@Override
	public Algorithm createAlgorithm() {
		return new ShortestPath(graph);
	}

	@Override
	public String getURLSuffix() {
		return "sps";
	}

	@Override
	public String getAlgName() {
		return "Shortest Path Simple";
	}

	@Override
	public int getVersion() {
		return 1;
	}

	@Override
	public List<Map<String, Object>> getPointConstraints() {
		return null;
	}

	@Override
	public Map<String, Object> getConstraints() {
		Map<String, Object> map = new HashMap<String, Object>(2);
		map.put("minPoints", Integer.valueOf(2));
		map.put("sourceIsTarget", Boolean.FALSE);
		return map;
	}

	@Override
	public boolean hidden() {
		return false;
	}

}

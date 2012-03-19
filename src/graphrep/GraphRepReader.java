package graphrep;

import java.io.IOException;
import java.io.InputStream;

/**
 * The interface implemented by classes that read a GraphRep from an InputStream
 */
public interface GraphRepReader {

	GraphRep createGraphRep(InputStream in) throws IOException;

}

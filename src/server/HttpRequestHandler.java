/**
 * $$\\ToureNPlaner\\$$
 */

package server;

import computecore.ComputeCore;
import computecore.ComputeRequest;
import computecore.RequestPoints;
import config.ConfigManager;
import database.DatabaseManager;
import database.RequestDataset;
import database.UserDataset;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.base64.Base64;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.util.CharsetUtil;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.*;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * This handler handles HTTP Requests on the normal operation socket including *
 * 
 * @author Niklas Schnelle, Peter Vollmer, Sascha Meusel
 * @version 0.1
 * 
 *          Initially based on: http://docs.jboss.org/netty/3.2/xref
 *          /org/jboss/netty/example/http/snoop/package-summary.html
 */
public class HttpRequestHandler extends SimpleChannelUpstreamHandler {
    
    private static Logger log = Logger.getLogger("server");

	/** ObjectMapper we can reuse **/
	private final ObjectMapper mapper;

	/** The ComputeCore managing the threads **/
	private final ComputeCore computer;

	private final Map<String, Object> serverInfo;

	private boolean isPrivate;

	private DatabaseManager dbm;

	private MessageDigest digester;

	private Responder responder;

	private static final class MapType extends
			TypeReference<Map<String, Object>> {
	}

	private static final MapType JSONOBJECT = new MapType();

	/**
	 * Constructs a new RequestHandler using the given ComputeCore and
	 * ServerInfo
	 * 
	 * @param cCore
	 * @param serverInfo
	 */
	public HttpRequestHandler(final ObjectMapper mapper,
			final ComputeCore cCore, final Map<String, Object> serverInfo) {
		super();
		final ConfigManager cm = ConfigManager.getInstance();
		this.mapper = mapper;
		this.computer = cCore;
		this.serverInfo = serverInfo;
		this.isPrivate = cm.getEntryBool("private", false);
		this.responder = null;

		if (isPrivate) {
			try {
				this.dbm = new DatabaseManager(cm.getEntryString("dburi",
						"jdbc:mysql://localhost:3306/"), cm.getEntryString(
						"dbname", "tourenplaner"), cm.getEntryString("dbuser",
						"tnpuser"), cm.getEntryString("dbpw", "toureNPlaner"));
				digester = MessageDigest.getInstance("SHA-1");
			} catch (SQLException e) {
				log.log(Level.SEVERE, "Can't connect to database, switching to public mode",e);
                
				this.isPrivate = false;
			} catch (NoSuchAlgorithmException e) {
				log.log(Level.SEVERE, "Can't load SHA-1 Digester. Will now switch to public mode",e);
				this.isPrivate = false;
			}
		} else {
			digester = null;
		}
	}

	/**
	 * Called when a message is received
	 */
	@Override
	public void messageReceived(final ChannelHandlerContext ctx,
			final MessageEvent e) throws Exception {

		final HttpRequest request = (HttpRequest) e.getMessage();
		final Channel channel = e.getChannel();
		// System.out.print(request.toString());
		// Handle preflighted requests so wee need to work with OPTION Requests
		if (request.getMethod().equals(HttpMethod.OPTIONS)) {
			handlePreflights(request, channel);
			return;
		}

		// Get the Requeststring e.g. /info
		final QueryStringDecoder queryStringDecoder = new QueryStringDecoder(
				request.getUri());

		final String path = queryStringDecoder.getPath();

		// DEBUG

		log.fine("Request for: " + path);
		log.finer("Request: " + request.getContent().toString(CharsetUtil.UTF_8));

		if (responder == null) {
			responder = new Responder(mapper, channel, isKeepAlive(request));
		}

		try {
			if ("/info".equals(path)) {

				handleInfo(request);

			} else if (path.startsWith("/alg")) {

				final String algName = queryStringDecoder.getPath()
						.substring(4);
				handleAlg(request, algName);

			} else if (isPrivate && "/registeruser".equals(path)) {

				handleRegisterUser(request);

			} else if (isPrivate && "/authuser".equals(path)) {

				handleAuthUser(request);

			} else if (isPrivate && "/getuser".equals(path)) {

				handleGetUser(request);

			} else if (isPrivate && "/updateuser".equals(path)) {

				handleUpdateUser(request);

			} else if (isPrivate && "/listrequests".equals(path)) {

				handleListRequests(request, queryStringDecoder.getParameters());

			} else if (isPrivate && "/listusers".equals(path)) {

				handleListUsers(request);

			} else {
				// Unknown request, close connection
				log.warning("An unknown URL was requested: "
								+ path);
				responder.writeErrorMessage("EUNKNOWNURL",
						"An unknown URL was requested", null,
						HttpResponseStatus.NOT_FOUND);
			}
		} catch (SQLException exSQL) {
			responder.writeErrorMessage("EDATABASE", "The server can't contact it's database", null, HttpResponseStatus.NOT_FOUND);
			exSQL.printStackTrace();
		}
	}

	/**
	 * 
	 * @param request
	 * @param algName
	 * @throws IOException
	 * @throws SQLException
	 *             Thrown if auth fails or logging of request fails
	 */
	private void handleAlg(final HttpRequest request, final String algName)
			throws IOException, SQLException {
		UserDataset userDataset = null;

		if (isPrivate) {
			userDataset = auth(request);
			if (userDataset == null) {
				responder.writeUnauthorizedClose();
				return;
			}
		}

		try {
			final ComputeRequest req = readComputeRequest(algName, responder,
					request);
			if (req != null) {

				RequestDataset requestDataset = null;

				if (isPrivate) {
					byte[] jsonRequest = request.getContent().array();
					requestDataset = dbm.addNewRequest(userDataset.id, algName,
							jsonRequest);
					req.setRequestID(requestDataset.id);


				}

				final boolean success = computer.submit(req);

				if (!success) {
					responder
							.writeErrorMessage(
									"EBUSY",
									"This server is currently too busy to fullfill the request",
									null,
									HttpResponseStatus.SERVICE_UNAVAILABLE);
					log.warning("Server had to deny algorithm request because of OVERLOAD");
					// Log failed requests because of full queue as failed, as
					// not pending and as paid
					// TODO specify this case clearly, maybe behavior should be
					// another
					requestDataset.failDescription = "This server is currently too busy to fullfill the request";
					requestDataset.hasFailed = true;
					requestDataset.isPending = true;
					requestDataset.isPaid = true;
					dbm.updateRequest(requestDataset);

				}
			}
		} catch (JsonParseException e) {
			responder.writeErrorMessage("EBADJSON", "Could not parse supplied JSON", e.getMessage(), HttpResponseStatus.UNAUTHORIZED);
		}

	}

	/**
	 * Extracts and parses the JSON encoded content of the given HttpRequest, in
	 * case of error sends a EBADJSON or HttpStatus.NO_CONTENT answer to the
	 * client and returns null, the connection will be closed afterwards.
	 * 
	 * @param responder
	 * @param request
	 * @throws IOException
	 */
	private Map<String, Object> getJSONContent(final Responder responder,
			final HttpRequest request) throws IOException {

		Map<String, Object> objmap = null;
		final ChannelBuffer content = request.getContent();
		if (content.readableBytes() > 0) {
			try {
				objmap = mapper.readValue(
						new ChannelBufferInputStream(content),
						new TypeReference<Map<String, Object>>() {
						});
			} catch (JsonParseException e) {
				responder.writeErrorMessage("EBADJSON",
						"Could not parse supplied JSON", e.getMessage(),
						HttpResponseStatus.UNAUTHORIZED);
				objmap = null;
			}

		} else {
			// Respond with No Content
			final HttpResponse response = new DefaultHttpResponse(HTTP_1_1,
					NO_CONTENT);
			// Write the response.
			final ChannelFuture future = responder.getChannel().write(response);
			future.addListener(ChannelFutureListener.CLOSE);
		}

		return objmap;
	}

	/**
	 * Reads a JSON encoded compute request from the content field of the given
	 * request
	 *
	 * @param algName
	 * @param responder
	 * @param request
	 * @return
	 * @throws IOException
	 * @throws JsonParseException
	 */
	private ComputeRequest readComputeRequest(final String algName,
			final Responder responder, final HttpRequest request)
			throws IOException, JsonParseException {

		Map<String, Object> constraints = null;
		final RequestPoints points = new RequestPoints();
		final ChannelBuffer content = request.getContent();
		if (content.readableBytes() > 0) {

			final JsonParser jp = mapper.getJsonFactory().createJsonParser(
					new ChannelBufferInputStream(content));
			jp.setCodec(mapper);

			if (jp.nextToken() != JsonToken.START_OBJECT) {
				throw new JsonParseException("Request contains no json object",
						jp.getCurrentLocation());
			}

			String fieldname;
			JsonToken token;
			Map<String, Object> pconsts;
			int lat = 0, lon = 0;
			while (jp.nextToken() != JsonToken.END_OBJECT) {
				fieldname = jp.getCurrentName();
				token = jp.nextToken(); // move to value, or
										// START_OBJECT/START_ARRAY
				if ("points".equals(fieldname)) {
					// Should be on START_ARRAY
					if (token != JsonToken.START_ARRAY) {
						throw new JsonParseException("points is no array",
								jp.getCurrentLocation());
					}
					// Read array elements
					while (jp.nextToken() != JsonToken.END_ARRAY) {
						pconsts = new HashMap<String, Object>();
						while (jp.nextToken() != JsonToken.END_OBJECT) {
							fieldname = jp.getCurrentName();
							token = jp.nextToken();

							if ("lt".equals(fieldname)) {
								lat = jp.getIntValue();
							} else if ("ln".equals(fieldname)) {
								lon = jp.getIntValue();
							} else {
								pconsts.put(fieldname,
										jp.readValueAs(Object.class));
							}
						}
						points.addPoint(lat, lon, pconsts);
					}

				} else if ("constraints".equals(fieldname)) {
					constraints = jp.readValueAs(JSONOBJECT);
				} else {
					// ignore for now TODO: user version string etc.
					if ((token == JsonToken.START_ARRAY)
							|| (token == JsonToken.START_OBJECT)) {
						jp.skipChildren();
					}
				}
			}

		} else {
			// Respond with No Content
			final HttpResponse response = new DefaultHttpResponse(HTTP_1_1,
					NO_CONTENT);
			// Write the response.
			final ChannelFuture future = responder.getChannel().write(response);
			future.addListener(ChannelFutureListener.CLOSE);
			log.warning("No Content");
			return null;
		}

		return new ComputeRequest(responder, algName, points, constraints);
	}

	private void handleListUsers(final HttpRequest request) {
		// TODO Auto-generated method stub

	}

	private void handleListRequests(final HttpRequest request,
			Map<String, List<String>> parameters) throws SQLException,
			JsonGenerationException, JsonMappingException, IOException {

		UserDataset user = null;
		user = auth(request);

		// authentication needed, auth(request) responses with error if auth
		// fails
		if (user == null) {
			return;
		}

		int userID = -1;
		if (parameters.containsKey("id")) {
			if (!user.admin) {
				responder
						.writeErrorMessage("ENOTADMIN", "You are not an admin", "You must be admin if you want to use the id parameter", HttpResponseStatus.FORBIDDEN);
				return;
			}

			try {
				userID = Integer.parseInt(parameters.get("id").get(0));
			} catch (NumberFormatException e) {
				userID = -1;
			}

			if (userID < 0) {
				responder
						.writeErrorMessage("ENOID", "The given user id is unknown to this server", "The given id is not an allowed number (positive or zero)", HttpResponseStatus.UNAUTHORIZED);
				return;
			}

		}

		int limit = extractPosIntParameter(parameters, "limit");
		int offset = extractPosIntParameter(parameters, "offset");

		if ((limit < 0) || (offset < 0)) {
			return;
		}

		if (userID < 0) {
			userID = user.id;
		}

		List<RequestDataset> requestDatasetList = null;
		requestDatasetList = dbm.getRequests(userID, limit, offset);

		List<Map<String, Object>> requestObjectList = new ArrayList<Map<String, Object>>();
		for (int i = 0; i < requestDatasetList.size(); i++) {
			requestObjectList.add(requestDatasetList.get(i)
					.getSmallRequestDatasetHashMap());
		}

		Map<String, Object> responseMap = new HashMap<String, Object>(2);
		responseMap.put("number", requestDatasetList.size());
		responseMap.put("requests", requestObjectList);

		responder.writeJSON(responseMap, HttpResponseStatus.OK);
		log.finest("ListRequests successful.");

	}

	private int extractPosIntParameter(Map<String, List<String>> parameters,
			String name) {
		int param = -1;

		if (!parameters.containsKey(name)) {
			responder.writeErrorMessage("E" + name.toUpperCase(), "The given " + name + " is invalid", "You must send a " + name + " parameter", HttpResponseStatus.NOT_ACCEPTABLE);
			return -1;
		}

		try {
			param = Integer.parseInt(parameters.get(name).get(0));
		} catch (NumberFormatException e) {
			param = -1;
		}

		if (param < 0) {
			responder.writeErrorMessage("E" + name.toUpperCase(), "The given "
					+ name + " is invalid", "You must send a " + name
					+ " parameter", HttpResponseStatus.NOT_ACCEPTABLE);

			return -1;
		}

		return param;
	}

	private void handleUpdateUser(final HttpRequest request) {
		// TODO Auto-generated method stub

	}

	private void handleGetUser(final HttpRequest request) {
		// TODO Auto-generated method stub

	}

	private void handleAuthUser(final HttpRequest request)
			throws JsonGenerationException, JsonMappingException, IOException,
			SQLException {
		UserDataset user = auth(request);
		if (user != null)
			responder.writeJSON(user, HttpResponseStatus.OK);

	}

	/**
	 * If authorization is okay, but no admin, registration fails. If no
	 * authorization as admin, the new registered user will not be registered as
	 * admin, even if json admin flag is true.
	 * 
	 * @param request
	 * @throws SQLFeatureNotSupportedException
	 * @throws SQLException
	 */
	private void handleRegisterUser(final HttpRequest request)
			throws IOException, SQLFeatureNotSupportedException, SQLException {

		UserDataset user = null;
		UserDataset authUser = null;

		// if no authorization header keep on with adding not verified user
		if (request.getHeader("Authorization") != null) {
			authUser = auth(request);
		     if (!authUser.admin) {
				responder.writeUnauthorizedClose();
				log.warning("RegisterUser failed, a logged in user has to be admin to register users.");
				return;
			}
		}

		Map<String, Object> objmap = getJSONContent(responder, request);

		// getJSONContent adds error-message to responder
		// if json object is bad or if there is no json object
		// so no further handling needed if objmap == null
		if (objmap == null) {
			log.warning("RegisterUser failed, bad json object.");
			return;
		}

		final String email = (String) objmap.get("email");
		final String pw = (String) objmap.get("password");
		final String firstName = (String) objmap.get("firstname");
		final String lastName = (String) objmap.get("lastname");
		final String address = (String) objmap.get("address");

		if ((pw == null) || (email == null) || (firstName == null)
				|| (lastName == null) || (address == null)) {
			// TODO maybe change error id and message
			responder
					.writeErrorMessage("EBADJSON", "Could not parse supplied JSON", "JSON user object was not correct " + "(needs email, password, firstname, lastname, address)", HttpResponseStatus.UNAUTHORIZED);
			return;
		}

		// TODO optimize salt-generation
		final Random rand = new Random();
		final StringBuilder saltBuilder = new StringBuilder(64);
		for (int i = 0; i < 4; i++) {
			saltBuilder.append(Long.toHexString(rand.nextLong()));
		}

		final String salt = saltBuilder.toString();

		final String toHash = generateHash(salt, pw);

		// if no authorization add not verified user
		if (authUser == null) {
			// if there is no authorization as admin, the new registered
			// user will
			// never be registered as admin, even if json admin flag is true
			user = dbm.addNewUser(email, toHash, salt, firstName, lastName,
					address, false);
		} else if (objmap.get("admin") != null) {

			user = dbm.addNewVerifiedUser(email, toHash, salt, firstName,
					lastName, address, (Boolean) objmap.get("admin"));
		}

		if (user == null) {
			responder.writeErrorMessage("EREGISTERED",
					"This email is already registered", null,
					HttpResponseStatus.FORBIDDEN);
			log.warning("RegisterUser failed, email is already registered.");
			return;
		} else {
			responder.writeJSON(user, HttpResponseStatus.OK);
			log.finest("HttpRequestHandler: RegisterUser succseeded.");
		}

	}

	protected String generateHash(final String salt, final String pw) {
		// Compute SHA1 of PW:SALT
		String toHash = pw + ":" + salt;

		final byte[] bindigest = digester.digest(toHash
				.getBytes(CharsetUtil.UTF_8));
		// Convert to Hex String
		final StringBuilder hexbuilder = new StringBuilder(bindigest.length * 2);
		for (byte b : bindigest) {
			hexbuilder.append(Integer.toHexString((b >>> 4) & 0x0F));
			hexbuilder.append(Integer.toHexString(b & 0x0F));
		}
		toHash = hexbuilder.toString();
		return toHash;
	}

	private void handleInfo(final HttpRequest request)
			throws JsonGenerationException, JsonMappingException, IOException {
		responder.writeJSON(serverInfo, HttpResponseStatus.OK);
	}

	/**
	 * Handles preflighted OPTION Headers
	 * 
	 * @param request
	 * @param channel
	 */
	private void handlePreflights(final HttpRequest request,
			final Channel channel) {
		boolean keepAlive = isKeepAlive(request);
		HttpResponse response;

		// We only allow POST and GET methods so only allow request when Method
		// is Post or Get
		final String methodType = request
				.getHeader("Access-Control-Request-Method");
		if ((methodType != null)
				&& (methodType.trim().equals("POST") || methodType.trim()
						.equals("GET"))) {
			response = new DefaultHttpResponse(HTTP_1_1, OK);
			response.addHeader("Connection", "Keep-Alive");
		} else {
			response = new DefaultHttpResponse(HTTP_1_1, FORBIDDEN);
			// We don't want to keep the connection now
			keepAlive = false;
		}

		final ArrayList<String> allowHeaders = new ArrayList<String>(2);
		allowHeaders.add("Content-Type");
		allowHeaders.add("Authorization");

		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
		response.setHeader(CONTENT_TYPE, "application/json");
		response.setHeader("Content-Length", "0");

		response.setHeader("Access-Control-Allow-Headers", allowHeaders);

		final ChannelFuture future = channel.write(response);
		if (!keepAlive) {
			future.addListener(ChannelFutureListener.CLOSE);
		}

	}

	/**
	 * Authenticates a Request using HTTP Basic Authentication and returns the
	 * UserDataset object of the authenticated user or null if authentication
	 * failed. Errors will be sent to the client as error messages see protocol
	 * specification for details. The connection will get closed after the error
	 * has been sent
	 *
	 * @return the UserDataset object of the user or null if auth failed
	 * @throws SQLException
	 */
	private UserDataset auth(final HttpRequest myReq) throws SQLException {
		String email, emailandpw, pw;
		UserDataset user = null;
		int index = 0;
		// Why between heaven and earth does Java have AES Encryption in
		// the standard library but not Base64 though it has it internally
		// several times
		emailandpw = myReq.getHeader("Authorization");
		if (emailandpw == null) {
			return null;
		}

		ChannelBuffer encodeddata;
		ChannelBuffer data;
		// Base64 is always ASCII
		encodeddata = ChannelBuffers.wrappedBuffer(emailandpw.substring(
				emailandpw.lastIndexOf(' ')).getBytes(CharsetUtil.US_ASCII));

		data = Base64.decode(encodeddata);
		// The string itself is utf-8
		emailandpw = data.toString(CharsetUtil.UTF_8);
		index = emailandpw.indexOf(':');
		if (index <= 0) {
			return null;
		}

		email = emailandpw.substring(0, index);
		pw = emailandpw.substring(index + 1);
		user = dbm.getUser(email);

		if (user == null) {
			responder.writeErrorMessage("EAUTH", "Wrong username or password",
					null, HttpResponseStatus.UNAUTHORIZED);

			return null;
		}

		// Compute SHA1 of PW:SALT
		final String toHash = generateHash(user.salt, pw);

		System.out.println(pw + ":" + user.salt + " : " + toHash);
		if (!user.passwordhash.equals(toHash)) {
			responder.writeErrorMessage("EAUTH", "Wrong username or password",
					null, HttpResponseStatus.UNAUTHORIZED);
			return null;
		}

		return user;
	}

	/**
	 * Called when an uncaught exception occurs
	 */
	@Override
	public void exceptionCaught(final ChannelHandlerContext ctx,
			final ExceptionEvent e) throws Exception {
		log.log(Level.WARNING, "Uhh, ohh", e);
		e.getChannel().close();
	}
}

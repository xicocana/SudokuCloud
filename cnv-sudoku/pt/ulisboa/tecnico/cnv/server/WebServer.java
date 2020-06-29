package pt.ulisboa.tecnico.cnv.server;


import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import pt.ulisboa.tecnico.cnv.solver.Solver;
import pt.ulisboa.tecnico.cnv.solver.SolverArgumentParser;
import pt.ulisboa.tecnico.cnv.solver.SolverFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;


import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.PutItemResult;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.util.TableUtils;

import BIT.*;

public class WebServer {

    static AmazonDynamoDB dynamoDB;

    static List<String> cacheCost;

    public static void main(final String[] args) throws Exception {

        System.out.println("Running WebServer on port 8000...");
        cacheCost = new ArrayList<>();
        //final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8000), 0);
        final HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        server.createContext("/sudoku", new MyHandler());
        // be aware! infinite pool of threads!
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        //System.out.println(server.getAddress().toString());
    }

    public static String parseRequestBody(InputStream is) throws IOException {
        InputStreamReader isr =  new InputStreamReader(is,"utf-8");
        BufferedReader br = new BufferedReader(isr);

        // From now on, the right way of moving from bytes to utf-8 characters:
        int b;
        StringBuilder buf = new StringBuilder(512);
        while ((b = br.read()) != -1) {
            buf.append((char) b);
        }

        br.close();
        isr.close();

        return buf.toString();
    }

    static class MyHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange t) throws IOException {

            // Get the query.
            final String query = t.getRequestURI().getQuery();
            System.out.println("> Query:\t" + query);


            // Break it down into String[].
            final String[] params = query.split("&");

            // Store as if it was a direct call to SolverMain.
            final ArrayList<String> newArgs = new ArrayList<>();
            for (final String p : params) {
                final String[] splitParam = p.split("=");
                newArgs.add("-" + splitParam[0]);
                newArgs.add(splitParam[1]);
            }
            newArgs.add("-b");
            newArgs.add(parseRequestBody(t.getRequestBody()));

            newArgs.add("-d");

            // Store from ArrayList into regular String[].
            final String[] args = new String[newArgs.size()];
            int i = 0;
            for(String arg: newArgs) {
                args[i] = arg;
                i++;
            }
            // Get user-provided flags.
            final SolverArgumentParser ap = new SolverArgumentParser(args);

            // Create solver instance from factory.
            final Solver s = SolverFactory.getInstance().makeSolver(ap);
            List<Float> listMetrics = SudokuTool.getNumber();
            System.out.println("listMetrics:       " + listMetrics.toString());

            try{
				Integer n1 = ap.getN1();
				Integer n2 = ap.getN2();
				Integer un = ap.getUn();
				String inputBoard = ap.getInputBoard();
				String puzzleBoard = ap.getPuzzleBoard();
            	SolverFactory.SolverType type = ap.getSolverStrategy();

            	String key = type.toString() + "_" + n1 + "_" + un;
            	if (!cacheCost.contains(key)) {
            		saveOnDB(listMetrics, type.toString(), n1, n2, un, inputBoard, puzzleBoard);
            	}

			


            }catch(InterruptedException ex){
            	//TODO something
            }
            
 			System.out.println("Aqui");
            //Solve sudoku puzzle
            JSONArray solution = s.solveSudoku();


            // Send response to browser.
            final Headers hdrs = t.getResponseHeaders();

            //t.sendResponseHeaders(200, responseFile.length());


            ///hdrs.add("Content-Type", "image/png");
            hdrs.add("Content-Type", "application/json");

            hdrs.add("Access-Control-Allow-Origin", "*");

            hdrs.add("Access-Control-Allow-Credentials", "true");
            hdrs.add("Access-Control-Allow-Methods", "POST, GET, HEAD, OPTIONS");
            hdrs.add("Access-Control-Allow-Headers", "Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers");

            t.sendResponseHeaders(200, solution.toString().length());


            final OutputStream os = t.getResponseBody();
            OutputStreamWriter osw = new OutputStreamWriter(os, "UTF-8");
            osw.write(solution.toString());
            osw.flush();
            osw.close();

            os.close();

            System.out.println("> Sent response to " + t.getRemoteAddress().toString());
        }
    }

     private static void saveOnDB(List<Float> list, String typeStrategy, Integer n1, Integer n2, Integer un, String inputBoard, String puzzleBoard) throws InterruptedException {
        Float dyn_method_count = (Float) list.get(0);
        Float dyn_bb_count = (Float) list.get(1);
        Float dyn_instr_count = (Float) list.get(2);
        Float instr_per_bb = 0F;
        Float instr_per_method = 0F;
        Float bb_per_method = 0F;
        ProfileCredentialsProvider credentialsProvider = new ProfileCredentialsProvider();
        try {
            credentialsProvider.getCredentials();
        } catch (Exception e) {
            throw new AmazonClientException(
                    "Cannot load the credentials from the credential profiles file. " +
                            "Please make sure that your credentials file is at the correct " +
                            "location (~/.aws/credentials), and is in valid format.",e);
        }
        dynamoDB = AmazonDynamoDBClientBuilder.standard().withCredentials(credentialsProvider).withRegion("us-east-1").build();

        try {
            String tableName = "sudoku-solver-saved-metrics";

            // Create a table with a primary hash key named 'name', which holds a string
            CreateTableRequest createTableRequest = new CreateTableRequest().withTableName(tableName)
                    .withKeySchema(new KeySchemaElement().withAttributeName("name").withKeyType(KeyType.HASH))
                    .withAttributeDefinitions(new AttributeDefinition().withAttributeName("name").withAttributeType(ScalarAttributeType.S))
                    .withProvisionedThroughput(new ProvisionedThroughput().withReadCapacityUnits(1L).withWriteCapacityUnits(1L));

            // Create table if it does not exist yet
            TableUtils.createTableIfNotExists(dynamoDB, createTableRequest);
            // wait for the table to move into ACTIVE state
            TableUtils.waitUntilActive(dynamoDB, tableName);

            Double cost = calculateRequestCost(un, n1, typeStrategy, dyn_instr_count, dyn_method_count);

            // Add an item
            Map<String, AttributeValue> item = newItem(typeStrategy, n1, un, dyn_method_count, dyn_instr_count, cost.intValue());
            PutItemRequest putItemRequest = new PutItemRequest(tableName, item);
            PutItemResult putItemResult = dynamoDB.putItem(putItemRequest);
            System.out.println("Result: " + putItemResult);

            String key = typeStrategy + "_" + n1 + "_" + un;
            cacheCost.add(key);
        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());
        }


    }

   private static Map<String, AttributeValue> newItem( String typeStrategy, Integer n1, Integer un, Float dyn_method_count, Float dyn_instr_count, int totalCost) {
      	
      	Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
        item.put("name", new AttributeValue(typeStrategy + "_" + n1 + "_" + un));
        item.put("algo_type", new AttributeValue(typeStrategy));
        item.put("puzzle_size_n1", new AttributeValue().withN(Integer.toString(n1)));
        item.put("puzzle_size_un", new AttributeValue().withN(Integer.toString(un)));
        item.put("#_methods", new AttributeValue().withN(Float.toString(dyn_method_count)));
        item.put("#_instr", new AttributeValue().withN(Float.toString(dyn_instr_count)));
        item.put("total_cost", new AttributeValue().withN(Integer.toString(totalCost)));

        return item;
    }

    public static double MAX_INSTR = 1020632975D;
    public static double MAX_METH = 36779D;

    public static double calculateRequestCost(double inc, double size, String algo, Float instr, Float meth){
        double param1 = 0D;
        param1 = (inc * 100) / (size * size);
        
        double param2 = 0;               // falta arranjar valores de jeito po param2
        if(algo.equals("BFS")){
            param2 = 50D;
        }else if(algo.equals("DLX")){
            param2 = 25D;
        }else if(algo.equals("CP")){
            param2 = 25D;
        }

        double param3 = 0D;
        param3 = 0.5*((instr * 100) / (MAX_INSTR)) + 0.5*((meth * 100) / (MAX_METH)) ;

        return 0.3*param1 + 0.2*param2 + 0.5*param3;
    }

    // function to generate a random string of length n 
    static String getAlphaNumericString(int n) 
    { 
  
        // chose a Character random from this String 
        String AlphaNumericString = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                                    + "0123456789"
                                    + "abcdefghijklmnopqrstuvxyz"; 
  
        // create StringBuffer size of AlphaNumericString 
        StringBuilder sb = new StringBuilder(n); 
  
        for (int i = 0; i < n; i++) { 
  
            // generate a random number between 
            // 0 to AlphaNumericString variable length 
            int index 
                = (int)(AlphaNumericString.length() 
                        * Math.random()); 
  
            // add Character one by one in end of sb 
            sb.append(AlphaNumericString 
                          .charAt(index)); 
        } 
  
        return sb.toString(); 
    } 
}

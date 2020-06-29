import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.*;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@SuppressWarnings("Duplicates")
public class LoadBalancer {

    private static final String imageId = "ami-02a6f631cb15164a9";
    private static AmazonEC2 ec2;
    private static Set<Instance> instances = null;
    private static  Map<Instance, Integer> InstanceWeight = new HashMap<>();

    @SuppressWarnings("FieldCanBeLocal")
    private static int MAX_INSTR = 1020632975;
    @SuppressWarnings("FieldCanBeLocal")
    private static int MAX_METH = 36779;

    private static Map<String, Double> cacheCost;

    public static void main(String[] args) throws Exception {

        System.out.println("===========================================");
        System.out.println("Welcome to the AWS Sudoku LoadBalancer");
        System.out.println("===========================================");

        cacheCost = new HashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0); //TODO: mudar port
        server.createContext("/sudoku", new LoadHandler());
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor); // creates a default executor
        server.start();
    }


    public static class LoadHandler implements HttpHandler {

        @Override
        public void handle(final HttpExchange t) throws IOException {
            init();
            handle(t, 3);
        }

        private void init() {

            /*
             * The ProfileCredentialsProvider will return your [default]
             * credential profile by reading from the credentials file located at
             * (~/.aws/credentials).
             */
            AWSCredentials credentials;
            try {
                credentials = new ProfileCredentialsProvider().getCredentials();
            } catch (Exception e) {
                throw new AmazonClientException(
                        "Cannot load the credentials from the credential profiles file. " +
                                "Please make sure that your credentials file is at the correct " +
                                "location (~/.aws/credentials), and is in valid format.",
                        e);
            }
            ec2 = AmazonEC2ClientBuilder.standard().withRegion("us-east-1").withCredentials(new AWSStaticCredentialsProvider(credentials)).build();
        }

        private void handle(final HttpExchange t, int tries) throws IOException {

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
            for (String arg : newArgs) {
                args[i] = arg;
                i++;
            }

            Instance instance;
            final String targetInstance;
            Double predicted = 0.0;

            instances = getProjRunningInstances();
            if (InstanceWeight.size() == 0) {
                for (Instance instanceVar : instances) {
                    InstanceWeight.put(instanceVar, 0);
                }
            } else {
                deleteOld();
            }


            if (instances.size() == 1) {
                instance = instances.iterator().next();
                targetInstance = instance.getPublicDnsName();
                System.out.println("Choosing instance : " + targetInstance);
            } else {
                predicted = predictRequestCost(Double.parseDouble(newArgs.get(3)), Double.parseDouble(newArgs.get(5)), newArgs.get(1));
                instance = chooseInstance(predicted);
                targetInstance = instance.getPublicDnsName();
                System.out.println("Choosing instance : " + targetInstance);
            }

            String body = args[11].replaceAll("\\[", "%5B");
            body = body.replaceAll("]", "%5D");

            final URL obj = new URL("http://" + targetInstance + ":8000" + replaceAddress(t.getRequestURI().toString() + "&b=" + body, targetInstance));
            System.out.println(obj);


            ExecutorService executor = Executors.newCachedThreadPool();
            Callable<Boolean> task = new Callable<Boolean>() {
                public Boolean call() throws IOException {
                    return getResponseAndSend(t, obj, targetInstance);
                }
            };

            Future<Boolean> future = executor.submit(task);
            try {
                //30 segundos de espera.. temos de fazer uns testes para chegar ao melhor valor
                future.get(2, TimeUnit.MINUTES);

            } catch (TimeoutException | InterruptedException | ExecutionException ex) {
                if (tries > 0){
                    System.out.println("try number " + tries + " failed to instance : " + targetInstance);
                    InstanceWeight.put(instance, InstanceWeight.get(instance) - predicted.intValue());
                    handle(t, tries - 1);
                }
            } finally {
                future.cancel(true); // may or may not desire this
            }

            InstanceWeight.put(instance, InstanceWeight.get(instance) - predicted.intValue());
        }

        private Boolean getResponseAndSend(HttpExchange t, URL obj, String targetInstance) throws IOException {
            try {

                HttpURLConnection con = (HttpURLConnection) obj.openConnection();
                con.setRequestMethod("GET");

                //READ Response
                BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
                String inputLine;
                //noinspection StringBufferMayBeStringBuilder
                StringBuffer reply = new StringBuffer();

                while ((inputLine = in.readLine()) != null) {
                    reply.append(inputLine);
                    reply.append(System.getProperty("line.separator"));
                }

                int responseCode = con.getResponseCode();
                if (responseCode != 200) {
                    System.out.println("Error: got response code : " + responseCode + "from " + targetInstance);
                }

                in.close();
                con.disconnect();

                String response = reply.toString();
                JSONParser jsonParser = new JSONParser();
                Object object = jsonParser.parse(response);
                JSONArray solution = (JSONArray) object;


                // Send response to browser.
                final Headers hdrs = t.getResponseHeaders();
                hdrs.add("Content-Type", "application/json");
                hdrs.add("Access-Control-Allow-Origin", "*");
                hdrs.add("Access-Control-Allow-Credentials", "true");
                hdrs.add("Access-Control-Allow-Methods", "POST, GET, HEAD, OPTIONS");
                hdrs.add("Access-Control-Allow-Headers", "Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers");

                t.sendResponseHeaders(200, solution.toString().length());
                final OutputStream os = t.getResponseBody();
                OutputStreamWriter osw = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                osw.write(solution.toString());
                osw.flush();
                osw.close();
                os.close();

                System.out.println("> Sent response to " + t.getRemoteAddress().toString());
            } catch (ParseException e) {
                e.printStackTrace();
                return false;
            }

            return true;
        }

        private String parseRequestBody(InputStream is) throws IOException {
            InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
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

        private Set<Instance> getProjRunningInstances() {
            Filter filterImageId = new Filter("image-id", Collections.singletonList(imageId));
            Filter filterTypeId = new Filter("instance-type", Collections.singletonList(InstanceType.T2Micro.toString()));
            Filter filterState = new Filter("instance-state-name", Collections.singletonList("running"));

            return getInstancesByFilters(Arrays.asList(filterImageId, filterTypeId, filterState));
        }

        private Set<Instance> getInstancesByFilters(List<Filter> filters) {
            return getInstances(filters, ec2);
        }

        private Set<Instance> getInstances(List<Filter> filters, AmazonEC2 ec2) {
            DescribeInstancesRequest request = new DescribeInstancesRequest();
            request.withFilters(filters);
            DescribeInstancesResult describeInstancesRequest = ec2.describeInstances(request);
            List<Reservation> reservations = describeInstancesRequest.getReservations();
            Set<Instance> instances = new HashSet<>();

            for (Reservation reservation : reservations) {
                instances.addAll(reservation.getInstances());
            }

            return instances;
        }

        private Instance chooseInstance(Double predicted) {

            Instance instance = null;
            int min = 999;

            Set<Map.Entry<Instance, Integer>> entries = InstanceWeight.entrySet();

            for (Map.Entry<Instance, Integer> entry : entries) {
                if (entry.getValue() < min) {
                    min = entry.getValue();
                    instance = entry.getKey();
                }
            }

            InstanceWeight.put(instance, InstanceWeight.get(instance) + predicted.intValue());

            return instance;
        }

        private void deleteOld() {
            //delete old ones from InstanceWeigts
            Set<Map.Entry<Instance, Integer>> entries = InstanceWeight.entrySet();

            for (Map.Entry<Instance, Integer> entry : entries) {
                if (!instances.contains(entry.getKey())) {
                    InstanceWeight.remove(entry.getKey());
                }
            }
        }

        private double predictRequestCost(double inc, double size, String algo) {
            String key = algo + "_" + size + "_" + inc;
            if (cacheCost.containsKey(key)) {
                return cacheCost.get(key);
            } else {
                List<Map<String, AttributeValue>> fromDB = getFromDB(algo, size, inc);
                if (fromDB != null && fromDB.size() == 1) {
                    return Double.parseDouble(fromDB.get(0).get("total_cost").getN());
                } else if (fromDB != null && fromDB.size() > 0) {
                    //Se existe mais do que 1 valor fazer a media
                    double total = 0;
                    for (Map<String, AttributeValue> value : fromDB) {
                        AttributeValue total_cost = value.get("total_cost");
                        double costFromDynamo = Double.parseDouble(total_cost.getN());
                        total += costFromDynamo;
                    }

                    return total / fromDB.size();
                }
            }

            double param1;
            param1 = (inc * 100) / (size * size);

            double param2 = 0;               // falta arranjar valores de jeito po param2

            switch (algo) {
                case "BFS":
                    param2 = 50;
                    break;
                case "DLX":
                    param2 = 25;
                    break;
                case "CP":
                    param2 = 25;
                    break;
            }

            double instr = MAX_INSTR * 0.5;
            double meth = MAX_METH * 0.5;

            double param3;
            param3 = 0.5 * ((instr * 100) / (MAX_INSTR)) + 0.5 * ((meth * 100) / (MAX_METH));

            return 0.3 * param1 + 0.2 * param2 + 0.5 * param3;
        }

        private List<Map<String, AttributeValue>> getFromDB(String typeStrategy, Double n1, Double un) {

            ProfileCredentialsProvider credentialsProvider = new ProfileCredentialsProvider();
            try {
                credentialsProvider.getCredentials();
            } catch (Exception e) {
                throw new AmazonClientException(
                        "Cannot load the credentials from the credential profiles file. " +
                                "Please make sure that your credentials file is at the correct " +
                                "location (~/.aws/credentials), and is in valid format.", e);
            }
            AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.standard().withCredentials(credentialsProvider).withRegion("us-east-1").build();

            try {
                String tableName = "sudoku-solver-saved-metrics";

                // Scan items for movies with a year attribute greater than 1985
                HashMap<String, Condition> scanFilter = new HashMap<>();

                Condition condition = new Condition()
                        .withComparisonOperator(ComparisonOperator.EQ.toString())
                        .withAttributeValueList(new AttributeValue(typeStrategy));
                scanFilter.put("algo_type", condition);


                Condition condition2 = new Condition()
                        .withComparisonOperator(ComparisonOperator.EQ.toString())
                        .withAttributeValueList(new AttributeValue().withN(Integer.toString(un.intValue())));
                scanFilter.put("puzzle_size_un", condition2);

                Condition condition3 = new Condition()
                        .withComparisonOperator(ComparisonOperator.EQ.toString())
                        .withAttributeValueList(new AttributeValue().withN(Integer.toString(n1.intValue())));
                scanFilter.put("puzzle_size_n1", condition3);

                ScanRequest scanRequest = new ScanRequest(tableName).withScanFilter(scanFilter);
                ScanResult scanResult = dynamoDB.scan(scanRequest);

                if (scanResult.getCount() == 0) {
                    //TODO - Addicionar novos filtros: quando nao ha com as 3 condicoes ir buscar todos do algo, depois verificar o mais perto
                    scanFilter = new HashMap<>();
                    scanFilter.put("algo_type", condition);
                    scanFilter.put("puzzle_size_n1", condition3);

                    int between = n1.intValue() == 9 ? 20 : n1.intValue() == 16 ? 35 : 50;
                    AttributeValue from = new AttributeValue().withN(Integer.toString(un.intValue() + between));
                    AttributeValue to = new AttributeValue().withN(Integer.toString(un.intValue() - between));
                    condition2 = new Condition()
                            .withComparisonOperator(ComparisonOperator.BETWEEN.toString())
                            .withAttributeValueList(to, from);
                    scanFilter.put("puzzle_size_un", condition2);

                    scanRequest = new ScanRequest(tableName).withScanFilter(scanFilter);
                    scanResult = dynamoDB.scan(scanRequest);
                } else if (scanResult.getCount() == 1) {
                    //TODO refactor
                    AttributeValue total_cost = scanResult.getItems().get(0).get("total_cost");
                    double costFromDynamo = Double.parseDouble(total_cost.getN());

                    String key = typeStrategy + "_" + n1 + "_" + un;
                    cacheCost.put(key, costFromDynamo);
                }


                return scanResult.getItems();


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
            return null;
        }

        private static String replaceAddress(String url, String address) {
            return url.replaceAll("[/][a-z]*[:]", "/" + address + ":");
        }

    }
}

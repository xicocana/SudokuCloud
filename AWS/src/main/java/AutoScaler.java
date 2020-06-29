import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;

import java.util.*;

@SuppressWarnings("Duplicates")
public class AutoScaler {


    private static final int MIN_INSTANCES = 1;
    private static final String imageId = "ami-02a6f631cb15164a9";
    private static final int LAUNCH_PERIOD_SECONDS = 60;
    private static final int CHECK_PERIOD_SECONDS = 90;
    private static AmazonCloudWatch cloudWatch;
    private static AmazonEC2 ec2;

    public static void main(String[] args) throws InterruptedException {

        System.out.println("===========================================");
        System.out.println("Welcome to the AWS Sudoku Auto Scaler");
        System.out.println("===========================================");
        init();
        startAutoScaler();
    }

    /**
     * The only information needed to create a client are security credentials
     * consisting of the AWS Access Key ID and Secret Access Key. All other
     * configuration, such as the service endpoints, are performed
     * automatically. Client parameters, such as proxies, can be specified in an
     * optional ClientConfiguration object when constructing a client.
     *
     * @see com.amazonaws.auth.BasicAWSCredentials
     * @see com.amazonaws.auth.PropertiesCredentials
     * @see com.amazonaws.ClientConfiguration
     */
    private static void init() {

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
        cloudWatch = AmazonCloudWatchClientBuilder.standard().withRegion("us-east-1").withCredentials(new AWSStaticCredentialsProvider(credentials)).build();
    }

    @SuppressWarnings("InfiniteLoopStatement")
    private static void startAutoScaler() throws InterruptedException {
        while (true) {
            Set<Instance> runningInstances = getProjRunningInstances();
            System.out.println("You have " + runningInstances.size() + " Amazon EC2 instance(s) running.");
            if (runningInstances.size() < MIN_INSTANCES) {
                System.out.println("Initializing initial Instances");
                launchInstances(MIN_INSTANCES - runningInstances.size());
                Thread.sleep(LAUNCH_PERIOD_SECONDS * 1000); // give some time for the new instances to launch
                continue;
            }

            calculateAvgCPUUtilization(runningInstances);
            Thread.sleep(CHECK_PERIOD_SECONDS * 1000);
        }
    }

    private static Set<Instance> getProjRunningInstances() {
        Filter filterImageId = new Filter("image-id", Collections.singletonList(imageId));
        Filter filterTypeId = new Filter("instance-type", Collections.singletonList(InstanceType.T2Micro.toString()));
        Filter filterState = new Filter("instance-state-name", Collections.singletonList("running"));

        return getInstancesByFilters(Arrays.asList(filterImageId, filterTypeId, filterState));
    }

    private static Set<Instance> getInstancesByFilters(List<Filter> filters) {
        return getInstances(filters, ec2);
    }

    private static void launchInstances(int n) {
        Tag tag = new Tag("Type", "WebServer");
        TagSpecification tagSpecification = new TagSpecification();
        tagSpecification.withTags(tag);
        tagSpecification.withResourceType(ResourceType.Instance);
        for (int i = 1; i <= n; i++) {
            RunInstancesRequest runInstancesRequest = new RunInstancesRequest();
            runInstancesRequest.withImageId(imageId)
                    .withInstanceType(InstanceType.T2Micro)
                    .withMinCount(1)
                    .withMaxCount(1)
                    .withMonitoring(true)
                    .withSecurityGroups("cnn-SSH/HTTP-SG")
                    .withTagSpecifications(tagSpecification);

            ec2.runInstances(runInstancesRequest);
            System.out.println("Launched new instance");
        }
    }

    private synchronized static void TerminateInstance(String instanceId) {
        if (instanceId != null) {
            TerminateInstancesRequest termInstanceReq = new TerminateInstancesRequest();
            termInstanceReq.withInstanceIds(instanceId);
            ec2.terminateInstances(termInstanceReq);
            System.out.println("Instance terminated: " + instanceId);
        } else {
            System.out.println("Instance terminating is INVALID");
        }

    }

    private static void calculateAvgCPUUtilization(Set<Instance> instances) throws InterruptedException {
        String instanceToShutDown = null;
        double lowestCPUUtilization = 200;
        double sumCPUUtilization = 0;
        double avgCPUUtilization;

        System.out.println("Picking instances to launch/kill");

        for (Instance instance : instances) {
            Double cpuUtilization = getCPUUtilization(instance);
            System.out.println("CPU UTILIZATION:" + cpuUtilization);
            if (Double.NEGATIVE_INFINITY == cpuUtilization) {
                continue;
            }

            sumCPUUtilization += cpuUtilization;
            if (cpuUtilization < lowestCPUUtilization) {
                lowestCPUUtilization = cpuUtilization;
                instanceToShutDown = instance.getInstanceId();
            }
        }

        avgCPUUtilization = sumCPUUtilization / instances.size();


        int instancesByFiltersSize = getProjRunningInstances().size();

        if (avgCPUUtilization > 60) { // TODO: adicionar constantes globais
            launchInstances(1);
            Thread.sleep(LAUNCH_PERIOD_SECONDS * 1000);
        } else if (avgCPUUtilization < 20 && instancesByFiltersSize > MIN_INSTANCES ) {
            TerminateInstance(instanceToShutDown);
        }
    }

    private static Double getCPUUtilization(Instance instance) {
        long offsetInMilliseconds = 1000 * CHECK_PERIOD_SECONDS;
        Dimension instanceDimension = new Dimension();
        instanceDimension.setName("InstanceId");
        instanceDimension.setValue(instance.getInstanceId());
        GetMetricStatisticsRequest request = new GetMetricStatisticsRequest()
                .withStartTime(new Date(new Date().getTime() - offsetInMilliseconds))
                .withNamespace("AWS/EC2")
                .withPeriod(60)
                .withMetricName("CPUUtilization")
                .withStatistics("Average")
                .withDimensions(instanceDimension)
                .withEndTime(new Date());
        GetMetricStatisticsResult getMetricStatisticsResult = cloudWatch.getMetricStatistics(request);
        List<Datapoint> dataPoints = getMetricStatisticsResult.getDatapoints();
        if (dataPoints.isEmpty()) { // Datapoints take some time to update
            return Double.NEGATIVE_INFINITY;
        } else {
            return dataPoints.get(dataPoints.size() - 1).getAverage();
        }
    }

    private static Set<Instance> getInstances(List<Filter> filters, AmazonEC2 ec2) {
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


}

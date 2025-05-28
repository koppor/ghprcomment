///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21+

//DEPS org.kohsuke:github-api:2.0-rc.3
//DEPS one.util:streamex:0.8.3
//DEPS me.tongfei:progressbar:0.10.1
//DEPS org.jline:jline-terminal:3.30.4
//DEPS org.eclipse.collections:eclipse-collections:11.1.0
//DEPS info.picocli:picocli:4.7.7
//DEPS org.yaml:snakeyaml:2.4
//DEPS org.jooq:jool:0.9.15
//DEPS org.tinylog:tinylog-api:2.7.0
//DEPS org.tinylog:tinylog-impl:2.7.0
//DEPS org.tinylog:slf4j-tinylog:2.7.0 // because of jgit
//FILES tinylog.properties

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedCollection;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jooq.lambda.Unchecked;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHWorkflowJob;
import org.kohsuke.github.GHWorkflowRun;
import org.kohsuke.github.GitHub;
import org.tinylog.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "ghprcomment",
        version = "ghprcomment 2024-08-22",
        mixinStandardHelpOptions = true,
        sortSynopsis = false)
public class ghprcomment implements Callable<Integer> {

    public static final int REPOSITORY_REFERENCE_ERROR = 1;
    private static final int GH_PR_COMMENT_YAML_NOT_FOUND = 2;

    private final String CONFIG_FILE_NAME = "ghprcomment";

    private final String MAGIC_COMMENT = "<!-- created by ghprcomment -->";

    private final Pattern MAGIC_COMMENT_V2 = Pattern.compile("<!-- ghprcomment (\\S+) -->");

    private final Pattern MAGIC_COMMENT_V3 = Pattern.compile(
            "<!-- ghprcomment\\n(?<workflowName>.+?)\\n(?<jobName>.+?)\\n-->"
    );

    private final List<Path> SEARCH_PATHS = List.of(Path.of("."), Path.of(".github"));

    private final List<Path> CONFIG_PATHS = SEARCH_PATHS.stream()
                                                        .flatMap(path -> Stream.of(path.resolve(CONFIG_FILE_NAME + ".yaml"), path.resolve(CONFIG_FILE_NAME + ".yml")))
                                                        .toList();

    @Option(names = {"-r", "--repository"}, description = "The GitHub repository in the form owner/repository. E.g., JabRef/jabref", required = true)
    private String repository;

    @Option(names = {"-w", "--workflow-run-id"}, required = true)
    private Long workflowRunId;

    // PR_NUMBER: ${{ github.event.number }}
    @Option(names = {"-p", "--pr-number"}, required = true)
    private Integer pullRequestNumber;

    public static void main(String... args) {
        CommandLine commandLine = new CommandLine(new ghprcomment());
        commandLine.parseArgs(args);
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Optional<Path> configPath = CONFIG_PATHS.stream()
                                                .filter(Files::exists)
                                                .findFirst();
        if (configPath.isEmpty()) {
            Logger.error("{} not found. Searched at {}.", CONFIG_FILE_NAME, CONFIG_PATHS);
            return GH_PR_COMMENT_YAML_NOT_FOUND;
        }

        Logger.info("Connecting to {}...", repository);
        GitHub gitHub = GitHub.connect();

        GHRepository gitHubRepository;
        try {
            gitHubRepository = gitHub.getRepository(repository);

            // We fetch the pull request early to ensure that the number is valid
            Logger.info("Pull Request number: {}", pullRequestNumber);
            GHPullRequest pullRequest = gitHubRepository.getPullRequest(pullRequestNumber);

            GHWorkflowRun workflowRun = gitHubRepository.getWorkflowRun(workflowRunId);
            String runWorkflowName = workflowRun.getName();
            Logger.info("workflowRunId: {} ({})", workflowRunId, runWorkflowName);
            Set<String> failedJobs = workflowRun.listAllJobs().toList().stream()
                                                .filter(job -> job.getConclusion() == GHWorkflowRun.Conclusion.FAILURE)
                                                .map(GHWorkflowJob::getName)
                                                .collect(Collectors.toSet());
            Logger.info("Failed jobs: {}", failedJobs);

            List<FailureComment> failureComments = getFailureComments(configPath.get());
            Logger.debug("# failure comments: {}", failureComments.size());
            Logger.debug("Failure comments: {}", 
                failureComments.stream()
                    .map(fc -> fc.jobName)
                    .collect(Collectors.joining(", "))
            );
            Logger.trace("Failure comments: {}", failureComments);

            Optional<FailureComment> commentToPost = failureComments.stream()
                                                                    .filter(fc -> failedJobs.contains(fc.jobName))
                                                                    .findFirst();
            Logger.debug("Found comment: {}", commentToPost);

            // Delete all previous comments
            // And collect all already posted comments
            Set<String> commentedFailedJobs = new HashSet<>();
            pullRequest.getComments().forEach(Unchecked.consumer(comment -> {
                String body = comment.getBody();
                Logger.trace("Comment body: {}", body);

                Matcher matcherV2 = MAGIC_COMMENT_V2.matcher(body);
                Matcher matcherV3 = MAGIC_COMMENT_V3.matcher(body);

                if (body.contains(MAGIC_COMMENT)) {
                    Logger.debug("Found V1 match - deleting {}", comment.getId());
                    comment.delete();
                } else if (matcherV2.find()) {
                    Logger.debug("Found V1 match - deleting {}", comment.getId());
                    comment.delete();
                } else if (matcherV3.find()) {
                    String workflowName = matcherV3.group("workflowName");
                    if (runWorkflowName.equals(workflowName)) {
                        String jobName = matcherV3.group("jobName");
                        Logger.debug("Found a match of workflow '{}' / job '{}'", workflowName, jobName);
                        if (failedJobs.contains(jobName)) {
                            Logger.debug("Still fails: {}", jobName);
                            boolean isCommentToPost = commentToPost.map(FailureComment::jobName)
                                                                   .filter(jobName::equals)
                                                                   .isPresent();
                            if (isCommentToPost) {
                                Logger.debug("Comment to post - deleting (to enable repost later)");
                                comment.delete();
                            } else {
                                Logger.debug("Comment already posted; and not most important to comment - keeping comment as is");
                                commentedFailedJobs.add(jobName);
                            }
                        } else {
                            Logger.debug("Found a match - job not failing any more - deleting {}", comment.getId());
                            comment.delete();
                        }
                    }
                }
            }));
            Logger.debug("Commented failed jobs: {}", commentedFailedJobs);

            if (failedJobs.isEmpty()) {
                // Do nothing if all previous comments have been deleted - and no new comments need to be posted
                Logger.info("No failed jobs found. Exiting.");
                return 0;
            }

            SequencedCollection<FailureComment> commentsToPost = new LinkedHashSet<>();
            commentToPost.ifPresent(commentsToPost::add);
            commentToPost.ifPresent(fc -> Logger.debug("Comment to post for failed job: {}", fc.jobName));

            // Add all "failed" always comments
            failureComments.stream()
                           .filter(fc -> fc.always)
                           .filter(fc -> failedJobs.contains(fc.jobName))
                           .forEach(commentsToPost::add);

            commentsToPost.forEach(Unchecked.consumer(fc -> {
                if (!commentedFailedJobs.contains(fc.jobName)) {
                    // Post only if comment not already posted
                    postComment(fc.message, runWorkflowName, fc.jobName, pullRequest);
                    Logger.info("Posting comment for failed job {}", fc.jobName);
                } else {
                    Logger.debug("Comment already posted for job {}", fc.jobName);
                }
            }));
        } catch (IllegalArgumentException e) {
            Logger.error("Error in repository reference {}", repository);
            return REPOSITORY_REFERENCE_ERROR;
        }
        return 0;
    }

    private void postComment(String message, String workflowName, String jobName, GHPullRequest pullRequest) throws Exception {
        Logger.debug("workflowName: {}, jobName: {}", workflowName, jobName);

        if (message == null) {
            Logger.error("Skipping empty message");
            return;
        }
        Logger.trace("message: {}", message);

        String body = String.format("%s\n\n<!-- ghprcomment\n%s\n%s\n-->", message, workflowName, jobName);

        Logger.trace("Creating PR comment {}...", body);
        pullRequest.comment(body);
    }

    private static List<FailureComment> getFailureComments(Path yamlFile) throws IOException {
        LoaderOptions options = new LoaderOptions();
        Yaml yaml = new Yaml(options);

        // SnakeYAML 2.2 cannot handle records (returns a LinkedList instead of record)
        // We do the conversion "by hand"
        List<Map<String, String>> failureComments;
        try (InputStream inputStream = Files.newInputStream(yamlFile)) {
            failureComments = yaml.load(inputStream);
        }
        Logger.trace("failureComments {}", failureComments);
        List<FailureComment> result = failureComments.stream().map(map -> {
            boolean alwaysValue = false;
            Object always = map.get("always");
            if (always == null) {
                alwaysValue = false;
            } else if (always instanceof Boolean theValue) {
                alwaysValue = theValue;
            } else if (always instanceof String theValue) {
                alwaysValue = Boolean.parseBoolean(theValue);
            } else {
                Logger.error("Unknown always value: {}", always);
            }
            return new FailureComment(map.get("jobName"), map.get("message"), alwaysValue);
        }).toList();
        Logger.trace("result {}", result);
        return result;
    }

    record FailureComment(
            String jobName,
            String message,
            boolean always) {
    }
}

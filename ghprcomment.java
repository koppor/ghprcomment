///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 21+

//DEPS org.kohsuke:github-api:1.324
//DEPS one.util:streamex:0.8.2
//DEPS me.tongfei:progressbar:0.10.1
//DEPS org.jline:jline-terminal:3.26.3
//DEPS org.eclipse.collections:eclipse-collections:11.1.0
//DEPS info.picocli:picocli:4.7.6
//DEPS org.yaml:snakeyaml:2.2
//DEPS org.jooq:jool:0.9.15
//DEPS org.tinylog:tinylog-api:2.7.0
//DEPS org.tinylog:tinylog-impl:2.7.0
//DEPS org.tinylog:slf4j-tinylog:2.7.0 // because of jgit
//FILES tinylog.properties

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.jooq.lambda.Unchecked;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestReviewEvent;
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

    @Option(names = { "-r", "--repository" }, description = "The GitHub repository in the form owner/repository. E.g., JabRef/jabref", required = true)
    private String repository;

    @Option(names = { "-w", "--workflow-run-id" }, required = true)
    private Long workflowRunId;

    // PR_NUMBER: ${{ github.event.number }}
    @Option(names = { "-p", "--pr-number" }, required = true)
    private Integer pullRequestNumber;

    public static void main(String... args)  {
        CommandLine commandLine = new CommandLine(new ghprcomment());
        commandLine.parseArgs(args);
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Path yamlConfigFile = Path.of(CONFIG_FILE_NAME + ".yaml");
        if (!Files.exists(yamlConfigFile)) {
            yamlConfigFile = Path.of(CONFIG_FILE_NAME + ".yml");
            if (!Files.exists(yamlConfigFile)) {
                Logger.error("{} not found", CONFIG_FILE_NAME);
                return GH_PR_COMMENT_YAML_NOT_FOUND;
            }
        }

        Logger.info("Connecting to {}...", repository);
        GitHub gitHub = GitHub.connect();

        GHRepository gitHubRepository;
        try {
            gitHubRepository = gitHub.getRepository(repository);

            // We fetch the pull request early to ensure that the number is valid
            GHPullRequest pullRequest = gitHubRepository.getPullRequest(pullRequestNumber);

            GHWorkflowRun workflowRun = gitHubRepository.getWorkflowRun(workflowRunId);
            Set<String> failedJobs = workflowRun.listAllJobs().toList().stream()
                                                .filter(job -> job.getConclusion() == GHWorkflowRun.Conclusion.FAILURE)
                                                .map(GHWorkflowJob::getName)
                                                .collect(Collectors.toSet());
            Logger.debug("Failed jobs: {}", failedJobs);
            Optional<FailureComment> commentToPost = getFailureComments(yamlConfigFile).stream()
                                                                         .filter(fc -> failedJobs.contains(fc.jobName))
                                                                         .findFirst();
            Logger.debug("Found comment: {}", commentToPost);
            commentToPost.ifPresent(Unchecked.consumer(comment -> pullRequest.createReview().event(GHPullRequestReviewEvent.COMMENT).body(comment.message).create()));
        } catch (IllegalArgumentException e) {
            Logger.error("Error in repository reference {}", repository);
            return REPOSITORY_REFERENCE_ERROR;
        }
        return 0;
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
        Logger.debug("failureComments {}", failureComments);
        List<FailureComment> result = failureComments.stream().map(map -> new FailureComment(map.get("jobName"), map.get("message"))).toList();
        Logger.debug("result {}", result);
        return result;
    }

    record FailureComment(String jobName, String message) {}
}

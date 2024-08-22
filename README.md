# GitHub Comment on PR

Comments on a pull request after a job failure with configured hints (based on the job).

## How to use

Customize `ghprcomment.yml`.
See [ghprcomment.yml](ghprcomment.yml) for an example.

The job names are ordered by priority.
The first job that failed is used to determine the message to post.

```yaml
- jobName: job1
  message: |
    Job 1 failed

    Please check your IDE configuration for proper import ordering
- jobName: job3
  message: |
    Job 3 failed

    Please run OpenRewrite.
- jobName: job2
  message: |
    Job 2 failed

    Please run "[Checkstyle](https://checkstyle.sourceforge.io/)" in your IDE and check for errors.
```

Create a new GitHub workflow [`pr-comment.yml`](.github/workflows/pr-comment.yml):

```yaml
name: Comment on PR

on:
  workflow_run:
    workflows: ["Check"]
    types:
      - completed

jobs:
  comment:
    # https://docs.github.com/en/actions/using-workflows/events-that-trigger-workflows#running-a-workflow-based-on-the-conclusion-of-another-workflow
    if: ${{ github.event.workflow_run.conclusion == 'failure' }}
    runs-on: ubuntu-latest
    permissions:
      actions: read
      contents: read
      pull-requests: write
    timeout-minutes: 10
    steps:
       - name: Download PR number
         uses: actions/download-artifact@v4
         with:
            name: pr_number
            github-token: ${{ secrets.GITHUB_TOKEN }}
            run-id: ${{ github.event.workflow_run.id }}
       - name: Read pr_number.txt
         id: read-pr_number
         run: |
            PR_NUMBER=$(cat pr_number.txt)
            echo "Read PR number $PR_NUMBER"
            echo "pr_number=$PR_NUMBER" >> $GITHUB_OUTPUT
       - name: Checkout
         if: ${{ steps.read-pr_number.outputs.pr_number != '' }}
         uses: actions/checkout@v4
         with:
            fetch-depth: '0'
            show-progress: 'false'
            token: ${{ secrets.GITHUB_TOKEN }}
       - name: jbang
         if: ${{ steps.read-pr_number.outputs.pr_number != '' }}
         uses: jbangdev/jbang-action@v0.117.1
         with:
            script: ghprcomment@koppor/ghprcomment
            scriptargs: "-r koppor/workflow-comment-test -p ${{ steps.read-pr_number.outputs.pr_number }} -w ${{ github.event.workflow_run.id }}"
            trust: https://github.com/koppor/ghprcomment/
         env:
            GITHUB_OAUTH: ${{ secrets.GITHUB_TOKEN }}
```

In the triggering workflow, you need to add a step to upload the pull request number:

```yaml
name: "Check"
...
jobs:
  upload-pr-number:
    runs-on: ubuntu-latest
    steps:
      - name: Create pr_number.txt
        run: echo "${{ github.event.number }}" > pr_number.txt
      - uses: actions/upload-artifact@v4
        with:
          name: pr_number
          path: pr_number.txt
```

## Development setup

1. [Install jbang](https://www.jbang.dev/documentation/guide/latest/installation.html#using-jbang).
   E.g.,
   - Linux/macOS: `curl -Ls https://sh.jbang.dev | bash -s - app setup` or
   - Windows (Powershell): `iex "& { $(iwr -useb https://ps.jbang.dev) } app setup"`
2. Set the environment variable `GITHUB_OAUTH` containing a [GitHub personal access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-personal-access-token-classic). See [GitHub API for Java](https://github-api.kohsuke.org/) for details.
3. `jbang ghprcomment@koppor/ghprcomment`

```terminal
Usage: jbang ghprcomment@koppor/ghprcomment [-hV] -r=<repository> -w=<workflowRunId>
                   -p=<pullRequestNumber>
  -h, --help      Show this help message and exit.
  -p, --pr-number=<pullRequestNumber>
  -r, --repository=<repository>
                  The GitHub repository in the form owner/repository. E.g.,
                    JabRef/jabref
  -V, --version   Print version information and exit.
  -w, --workflow-run-id=<workflowRunId>
```

Example:

```terminal
Usage: jbang gcl@koppor/ghprcomment --repository JabRef/jabref --workflow-run-id 123456789 --pr-number 1234
```

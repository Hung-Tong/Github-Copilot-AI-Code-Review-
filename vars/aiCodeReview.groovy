def call(Map config = [:]) {
    // Required params
    def gitlabProjectPath = config.gitlabProjectPath
        ?: error("'gitlabProjectPath' is required")
    def projectId = config.projectId
        ?: error("'projectId' is required")

    // Credential ID for Copilot token — default to org-wide, override per project
    def copilotCredentialId = config.copilotCredentialId ?: 'github-copilot-token'

    pipeline {
        agent { label 'AICodeReview' }

        environment {
            GLAB_TOKEN           = credentials('gitlab-token')
            COPILOT_GITHUB_TOKEN = credentials("${copilotCredentialId}")
            GITLAB_HOST          = 'https://gitlab.com'
            GITLAB_PROJECT_PATH  = "${gitlabProjectPath}"
            GITLAB_PROJECT_ID    = "${projectId}"
        }

        parameters {
            string(name: 'MR_IID', defaultValue: '', description: 'MR IID to review')
        }

        stages {
            stage('Verify Tools') {
                steps {
                    sh '''
                        copilot --version
                        glab --version
                    '''
                }
            }

            stage('Authenticate') {
                steps {
                    sh '''
                        glab auth login \
                            --hostname "${GITLAB_HOST}" \
                            --token    "${GLAB_TOKEN}"
                            
                        echo "${COPILOT_GITHUB_TOKEN}" | gh auth login --with-token
                    '''
                }
            }

            stage('Resolve MR') {
                steps {
                    script {
                        def mrIid = params.MR_IID?.trim() ?: env.gitlabMergeRequestIid ?: ''
                        if (!mrIid) {
                            error("Could not determine MR IID. Pass MR_IID parameter or trigger via GitLab webhook.")
                        }
                        env.MR_IID = mrIid
                    }
                }
            }

            stage('Fetch MR Data') {
                steps {
                    sh '''
                        glab mr view "${MR_IID}" --repo "${GITLAB_PROJECT_PATH}" > mr_meta.txt
                        glab mr diff "${MR_IID}" --repo "${GITLAB_PROJECT_PATH}" > mr_diff.txt
                    '''
                }
            }

            stage('AI Code Review') {
                steps {
                    sh '''
                        MR_META=$(cat mr_meta.txt)
                        MR_DIFF=$(cat mr_diff.txt)

                        PROMPT="You are a senior software engineer performing a code review.

Here is the merge request metadata:
${MR_META}

Here is the diff:
${MR_DIFF}

Write a structured review using exactly these sections (no additional headers or labels):
1. **Summary** – What does this MR do?
2. **Changes** – Key files and logic changed
3. **Issues** – Bugs, security concerns, logic errors
4. **Suggestions** – Non-blocking improvements
5. **Verdict** – LGTM / Needs Changes / Needs Discussion

Then output the exact delimiter on its own line:
---INLINE_JSON---

Then output a JSON array of inline comments for specific lines. Each item must have:
  - new_path: relative file path in the repo (string)
  - new_line: line number in the new version of the file (integer)
  - body: the comment text (string)
  - severity: one of 'issue', 'suggestion' (string)
Only include items for lines that actually appear in the diff above.
If there are no inline comments, output an empty array: []
Output ONLY the raw JSON array after the delimiter — no markdown fences, no extra text."

                        copilot --model grok-4.6 --prompt "$PROMPT" --silent > review_raw.txt

                        # Split on ---INLINE_JSON--- delimiter
                        awk '/^---INLINE_JSON---$/{found=1; next} !found{print}' review_raw.txt > review.txt
                        awk '/^---INLINE_JSON---$/{found=1; next} found{print}' review_raw.txt > review_inline.json

                        echo "=== Summary Review ==="
                        cat review.txt
                        echo "=== Inline Comments JSON ==="
                        cat review_inline.json
                    '''
                }
            }

            stage('Post Comment') {
                steps {
                    sh '''
                        INLINE_COUNT=$(jq \'length\' review_inline.json 2>/dev/null || echo 0)

                        {
                          printf \'## 🤖 Copilot AI Code Review\n\n\'
                          printf \'_Automated review by GitHub Copilot via Jenkins._\n\n---\n\'
                          cat review.txt
                          printf "\n\n---\n_Inline comments posted: ${INLINE_COUNT}_ | _Re-run: trigger the **mr-ai-review** job._\n"
                        } > comment.txt

                        glab mr note "${MR_IID}" \
                            --repo    "${GITLAB_PROJECT_PATH}" \
                            --message "$(cat comment.txt)"
                    '''
                }
            }

            stage('Fetch Diff Refs') {
                steps {
                    sh '''
                        DIFF_REFS=$(curl --silent --fail \
                            --header "PRIVATE-TOKEN: ${GLAB_TOKEN}" \
                            "${GITLAB_HOST}/api/v4/projects/${GITLAB_PROJECT_ID}/merge_requests/${MR_IID}" \
                            | jq -r '.diff_refs')

                        echo "$DIFF_REFS" | jq -r '.base_sha'  > base_sha.txt
                        echo "$DIFF_REFS" | jq -r '.start_sha' > start_sha.txt
                        echo "$DIFF_REFS" | jq -r '.head_sha'  > head_sha.txt

                        echo "base_sha  : $(cat base_sha.txt)"
                        echo "start_sha : $(cat start_sha.txt)"
                        echo "head_sha  : $(cat head_sha.txt)"
                    '''
                }
            }

            stage('Post Inline Comments') {
                steps {
                    sh '''
                        BASE_SHA=$(cat base_sha.txt)
                        START_SHA=$(cat start_sha.txt)
                        HEAD_SHA=$(cat head_sha.txt)

                        # Validate inline JSON is a non-empty array before iterating
                        COUNT=$(jq \'length\' review_inline.json 2>/dev/null || echo 0)
                        echo "Inline comments to post: $COUNT"

                        i=0
                        while [ "$i" -lt "$COUNT" ]; do
                            NEW_PATH=$(jq -r --argjson i "$i" '.[$i].new_path' review_inline.json)
                            NEW_LINE=$(jq -r --argjson i "$i" '.[$i].new_line' review_inline.json)
                            BODY=$(jq -r --argjson i "$i" '.[$i].body' review_inline.json)
                            SEVERITY=$(jq -r --argjson i "$i" '.[$i].severity // "suggestion"' review_inline.json)
                            # Strip any leading severity label the AI may have included (e.g. "Nitpick: ", "Issue: ")
                            BODY=$(echo "$BODY" | sed -E 's/^(Issue|Suggestion|issue|suggestion):[[:space:]]*//')

                            PAYLOAD=$(jq -n \
                                --arg body "[$SEVERITY] $BODY" \
                                --arg position_type "text" \
                                --arg new_path "$NEW_PATH" \
                                --argjson new_line "$NEW_LINE" \
                                --arg base_sha "$BASE_SHA" \
                                --arg start_sha "$START_SHA" \
                                --arg head_sha "$HEAD_SHA" \
                                \'{body: $body, position: {position_type: $position_type, new_path: $new_path, new_line: $new_line, base_sha: $base_sha, start_sha: $start_sha, head_sha: $head_sha}}\')

                            HTTP_CODE=$(curl --silent --output /dev/null --write-out "%{http_code}" \
                                --header "PRIVATE-TOKEN: ${GLAB_TOKEN}" \
                                --header "Content-Type: application/json" \
                                --data "$PAYLOAD" \
                                "${GITLAB_HOST}/api/v4/projects/${GITLAB_PROJECT_ID}/merge_requests/${MR_IID}/discussions")

                            if [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 300 ]; then
                                echo "Posted inline comment $((i+1))/$COUNT on ${NEW_PATH}:${NEW_LINE} (HTTP $HTTP_CODE)"
                            else
                                echo "WARNING: Failed to post inline comment $((i+1))/$COUNT on ${NEW_PATH}:${NEW_LINE} (HTTP $HTTP_CODE) — skipping"
                            fi

                            i=$((i+1))
                        done
                    '''
                }
            }
        }

        post {
            failure {
                sh """
                    printf '## 🤖 Copilot Review — Failed\n\nCheck [Jenkins build](${env.BUILD_URL}).\n' > fail_comment.txt
                    glab mr note "${env.MR_IID}" \
                        --repo "${env.GITLAB_PROJECT_PATH}" \
                        --message "\$(cat fail_comment.txt)" || true
                """
            }
            always {
                sh 'rm -f mr_meta.txt mr_diff.txt review_raw.txt review.txt review_inline.json base_sha.txt start_sha.txt head_sha.txt comment.txt fail_comment.txt || true'
                cleanWs()
            }
        }
    }
}

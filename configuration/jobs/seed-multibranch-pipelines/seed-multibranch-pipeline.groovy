#!/usr/bin/env groovy
// Groovy script used to seed Jenkins with multi-branch pipeline jobs:
// 1. Call GitLab API to get each git project in a given group
// 2. Check if project is archived, if so skip it.
// 3. Check if there is a Jenkinsfile (on master) in each of the found projects
// 4. Generate a pipeline using the Jenkinsfile and add it to the queue on first creation
// 5. Every 10 mins run again


// GITLAB
def gitlabHost = System.getenv("GITLAB_HOST") ?: "https://gitlab.apps.proj.example.com"
def gitlabToken = System.getenv("GITLAB_TOKEN")
def groupName = System.getenv("GITLAB_GROUP_NAME") ?: "rht-labs"
def gitlabProjectsApi = new URL("${gitlabHost}/api/v4/groups/${groupName}/projects?per_page=100")

// GITHUB
def githubHost = "https://api.github.com"
// Token needed for rate limiting issues....
def githubToken = System.getenv("GITHUB_TOKEN")
def githubAccount = System.getenv("GITHUB_ACCOUNT")
def githubOrg = System.getenv("GITHUB_ORG") ?: false
//eg  https://api.github.com/users/springdo/repos or 

// BITBUCKET
def bitbucketHost = System.getenv("BITBUCKET_HOST") ?: "https://bitbucket.org/repo"
def bitbucketUser = System.getenv("BITBUCKET_USER")
def bitbucketPassword = System.getenv("BITBUCKET_PASSWORD")
def bitbucketProjectKey = System.getenv("BITBUCKET_PROJECT_KEY") ?: "rht-labs"
def bitbucketProjectsApi = new URL("${bitbucketHost}/rest/api/1.0/projects/${bitbucketProjectKey}/repos?limit=100")
def bitbucketAuth = (bitbucketUser+":"+bitbucketPassword).getBytes().encodeBase64().toString();

def githubProjects = githubOrg ? new URL("${githubHost}/orgs/${githubAccount}/repos?per_page=100") : new URL("${githubHost}/users/${githubAccount}/repos?per_page=100")

def createMultibranchPipelineJob(project, gitPath, jte) {
    def buildNamespace = System.getenv("BUILD_NAMESPACE") ?: "ocp-ci-cd"
    def buildGitAuthSecret = System.getenv("BUILD_GIT_AUTH_SECRET") ?: "git-auth"
    def jteProject = System.getenv("JTE_PROJECT") ?: "https://gitlab.apps.proj.example.com/rht-labs/pipeline-template-configuration.git"
    def pipelineConfigDir = System.getenv("JTE_PIPELINE_DIR") ?: "pipeline-configuration"
    def librariesDir = System.getenv("JTE_LIBRARIES_DIR") ?: "libraries"
    def credentialsId = System.getenv("SSH_ACCESS_KEY") ?: "Access-Key"

    // Build Jenkins multibranch jobs
    multibranchPipelineJob(project) {
        branchSources {
            git {
                id("${project}")
                remote(gitPath)
                credentialsId("${credentialsId}")
            }
        }
        triggers {
            computedFolderWebHookTrigger {
              // The token to match with webhook token.
              token(project)
            }
        }
        orphanedItemStrategy {
            discardOldItems {
              // Set to 0 to autoprune jobs once branch is deleted 
              numToKeep(0)
            }
        }

        // jte == boolean
        if (jte) {
          println("appending config for JTE to job");
          factory {
              templateBranchProjectFactory {
                  filterBranches(true)
              }
          }
          properties {
            templateConfigFolderProperty {
              tier {
                configurationProvider {
                  scmPipelineConfigurationProvider {
                    baseDir("${pipelineConfigDir}")
                    scm {
                      gitSCM {
                        userRemoteConfigs {
                          userRemoteConfig {
                            url("${jteProject}")
                            refspec("master")
                            name("jte-config")
                            credentialsId("${buildNamespace}-${buildGitAuthSecret}")
                            }
                        }
                        doGenerateSubmoduleConfigurations(false)
                        browser {}
                        gitTool(null)
                        }
                      }
                    }
                  }
                  librarySources {
                    librarySource {
                      libraryProvider {
                        scmLibraryProvider {
                          baseDir("${librariesDir}")
                          scm {
                            gitSCM {
                              userRemoteConfigs {
                                userRemoteConfig {
                                    url("${jteProject}")
                                    name("library-config")
                                    refspec("master")
                                    credentialsId("${buildNamespace}-${buildGitAuthSecret}")
                                }
                              }
                              doGenerateSubmoduleConfigurations(false)
                              browser {}
                              gitTool(null)
                            }
                          }
                        }
                      }
                    }
                  }
              }
            }
          }
        }
    }
}


def addJobToQueue(project){
  if (!jenkins.model.Jenkins.instance.getItemByFullName(project)) {
    print "About to create ${project} for the first time, this will result in a triggering the build after this run to prepare the ${project} pipeline\n\n"
    queue(project)
  }
}
// if GITLAB* set ....
println "Before starting to scan bitbucket projects in ${bitbucketProjectsApi}"
if (gitlabToken) {
  try {
      def projects = new groovy.json.JsonSlurper().parse(gitlabProjectsApi.newReader(requestProperties: ['PRIVATE-TOKEN': gitlabToken]))

      projects.each {
          def project = "${it.path}"
          def gitPath = it.http_url_to_repo

          if (it.archived) {
              println "skipping project ${project} because it has been archived\n\n"
              return
          }

          // 1. Check for "${gitlabHost}/api/v4/projects/${it.id}/repository/files/pipeline_config.groovy?ref=master"
                // => JTE
          // 2. Check for Jenkinsfile
                // => Jenkins classic
          // else - bail and do nothing
          try {
              def filesApi = new URL("${gitlabHost}/api/v4/projects/${it.id}/repository/files/pipeline_config.groovy?ref=master")
              def files = new groovy.json.JsonSlurper().parse(filesApi.newReader(requestProperties: ['PRIVATE-TOKEN': gitlabToken]))
              println "😘 JTE pipeline_config.groovy found in ${project} 🥳"
              createMultibranchPipelineJob(project, gitPath, true)
              addJobToQueue(project)
              return;

          }
          catch(Exception e) {
              println e
              println "JTE pipeline_config.groovy not found in ${project}. Checking for Jenkinsfile \n\n"
          }
          try {
              def filesApi = new URL("${gitlabHost}/api/v4/projects/${it.id}/repository/files/Jenkinsfile?ref=master")
              def files = new groovy.json.JsonSlurper().parse(filesApi.newReader(requestProperties: ['PRIVATE-TOKEN': gitlabToken]))
              println "😘 Jenkinsfile found in ${project} 🥳"
              createMultibranchPipelineJob(project, gitPath, false)
              addJobToQueue(project)
          }
          catch(Exception e) {
              println e
              println "skipping project ${project} because it has no Jenkinsfile\n\n"
          }
      }
  } catch(Exception e) {
      print "\n\n Please make sure you have set  GITLAB_HOST, GITLAB_TOKEN and GITLAB_GROUP_NAME in your deploy config for Jenkins \n\n\n"
      throw e
  }
} else if (githubAccount) {
  try {
      def projects = githubToken ? new groovy.json.JsonSlurper().parse(githubProjects.newReader(requestProperties: ['Authorization': "token ${githubToken}"])) : new groovy.json.JsonSlurper().parse(githubProjects.newReader())

      projects.each {
          def project = it.name
          def gitPath = it.clone_url

          if (it.archived) {
              print "skipping project ${project} because it has been archived\n\n"
              return
          }

          try {
            // https://api.github.com/repos/$ORG or $USER/name/contents/Jenkinsfile
              def filesApi = new URL("${githubHost}/repos/${githubAccount}/${project}/contents/pipeline_config.groovy")
              def files =  githubToken ? new groovy.json.JsonSlurper().parse(filesApi.newReader(requestProperties: ['Authorization': "token ${githubToken}"])) : new groovy.json.JsonSlurper().parse(filesApi.newReader())

              println "😘 JTE pipeline_config.groovy found in ${project} 🥳"
              createMultibranchPipelineJob(project, gitPath, true)
              addJobToQueue(project)
              return;

          }
          catch(Exception e) {
              println e
              println "JTE pipeline_config.groovy not found in ${project}. Checking for Jenkinsfile \n\n"
          }
          try {
              def filesApi = new URL("${githubHost}/repos/${githubAccount}/${project}/contents/Jenkinsfile")
              def files =  githubToken ? new groovy.json.JsonSlurper().parse(filesApi.newReader(requestProperties: ['Authorization': "token ${githubToken}"])) : new groovy.json.JsonSlurper().parse(filesApi.newReader())
              println "😘 Jenkinsfile found in ${project} 🥳"
              createMultibranchPipelineJob(project, gitPath, false)
              addJobToQueue(project)
          }
          catch(Exception e) {
              println e
              println "skipping project ${project} because it has no Jenkinsfile\n\n"
          }
      }
  } catch(Exception e) {
      print "\n\n Oops! something went wrong..... Try setting the GITHUB_* Env Vars \n\n\n"
      throw e
  }
} else if (bitbucketAuth) {
  try {
      def process = "curl -s -k -u ${bitbucketUser}:${bitbucketPassword} ${bitbucketProjectsApi}".execute()
      process.waitFor()
      def projects = new groovy.json.JsonSlurper().parseText(process.text)
      projects.values.each {
            def repositorySlug = it.slug
            def repoPath = ""
            def clone = it.links.clone
            clone.each {
                def name = it.name
                if(name == "ssh") {
                    repoPath = it.href
                }
            }
            def project = repositorySlug
            if (it.archived) {
                println "skipping project ${repositorySlug} because it has been archived\n\n"
                return
            }
    
            // 1. Check for "${gitlabHost}/api/v4/projects/${it.id}/repository/files/pipeline_config.groovy?ref=master"
                // => JTE
            // 2. Check for Jenkinsfile
                // => Jenkins classic
            // else - bail and do nothing
            try {
                process = "curl -s -k -u ${bitbucketUser}:${bitbucketPassword} ${bitbucketHost}/rest/api/1.0/projects/${bitbucketProjectKey}/repos/${repositorySlug}/browse/pipeline_config.groovy?ref=master".execute()
                process.waitFor()
                def response = new groovy.json.JsonSlurper().parseText(process.text)
                def errors = response.errors
                if (errors) {
                    throw new Exception("${errors.message}")
                }                
                println "😘 JTE pipeline_config.groovy found in ${repositorySlug} 🥳\n"
                createMultibranchPipelineJob(project, repoPath, true)
                addJobToQueue(project)
                return;
            }
            catch(Exception e) {
                println e
                println "JTE pipeline_config.groovy not found in ${project}. Checking for Jenkinsfile...."
            }

            try {
                process = "curl -s -k -u ${bitbucketUser}:${bitbucketPassword} ${bitbucketHost}/rest/api/1.0/projects/${bitbucketProjectKey}/repos/${repositorySlug}/browse/Jenkinsfile?ref=master".execute()
                process.waitFor()
                def response = new groovy.json.JsonSlurper().parseText(process.text)
                def errors = response.errors
                if (errors) {
                    throw new Exception("${errors.message}")
                }
                println "😘 Jenkinsfile found in ${repositorySlug} 🥳 \n"
                createMultibranchPipelineJob(project, repoPath, false)
                addJobToQueue(project)
            }
            catch(Exception e) {
                println e
                println "skipping project ${repositorySlug} because it has no Jenkinsfile \n"
            }
        }
    } catch(Exception e) {
      print "\n\n Please make sure you have set BITBUCKET_HOST, BITBUCKET_USER, BITBUCKET_PASSWORD and BITBUCKET_PROJECT_KEY in your deploy config for Jenkins \n\n\n"
      throw e
    }
} else {
    print "\n\n No tokens set in the Environment eg GITHUB* or GITLAB or BITBUCKET* so not sure what to do ..... 路‍♂️ \n\n\n"
}
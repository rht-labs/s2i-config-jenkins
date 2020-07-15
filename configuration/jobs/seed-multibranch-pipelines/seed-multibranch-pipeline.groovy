// Groovy script used to seed Jenkins with multi-branch pipeline jobs:
// 1. Call GitLab API to get each git project in a given group
// 2. Check if project is archived, if so skip it.
// 3. Check if there is a Jenkinsfile (on master) in each of the found projects
// 4. Generate a pipeline using the Jenkinsfile and add it to the queue on first creation
// 5. Every 10 mins run again


// GITLAB
def gitlabHost = System.getenv("GITLAB_HOST") ?: "https://gitlab.apps.proj.example.com"
def gitlabToken = System.getenv("GITLAB_TOKEN")
def projectName = System.getenv("GITLAB_GROUP_NAME") ?: "rht-labs"
def gitlabProjectsApi = new URL("${gitlabHost}/api/v4/groups/${projectName}/projects?per_page=100")


// GITHUB
def githubHost = "https://api.github.com"
// Token needed for rate limiting issues....
def githubToken = System.getenv("GITHUB_TOKEN")
def githubAccount = System.getenv("GITHUB_ACCOUNT")
def githubOrg = System.getenv("GITHUB_ORG") ?: false
//eg  https://api.github.com/users/springdo/repos or 

def githubProjects = githubOrg ? new URL("${githubHost}/orgs/${githubAccount}/repos?per_page=100") : new URL("${githubHost}/users/${githubAccount}/repos?per_page=100")


def createMultibranchPipelineJob(project, gitPath) {
    def buildNamespace = System.getenv("BUILD_NAMESPACE") ?: "labs-ci-cd"
    def buildGitAuthSecret = System.getenv("BUILD_GIT_AUTH_SECRET") ?: "git-auth"

    // Build Jenkins multibranc jobs
    multibranchPipelineJob(project) {
        branchSources {
            git {
                id("${project}")
                remote(gitPath)
                credentialsId("${buildNamespace}-${buildGitAuthSecret}")
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
    }
}

def addJobToQueue(project){
  if (!jenkins.model.Jenkins.instance.getItemByFullName(project)) {
    print "About to create ${project} for the first time, this will result in a triggering the build after this run to prepare the ${project} pipeline\n\n"
    queue(project)
  }
}
// if GITLAB* set ....
if (gitlabToken) {
  try {
      def projects = new groovy.json.JsonSlurper().parse(gitlabProjectsApi.newReader(requestProperties: ['PRIVATE-TOKEN': gitlabToken]))

      projects.each {
          def project = "${it.path}"
          def gitPath = it.http_url_to_repo

          if (it.archived) {
              print "skipping project ${project} because it has been archived\n\n"
              return
          }

          try {
              def filesApi = new URL("${gitlabHost}/api/v4/projects/${it.id}/repository/files/Jenkinsfile?ref=master")
              def files = new groovy.json.JsonSlurper().parse(filesApi.newReader(requestProperties: ['PRIVATE-TOKEN': gitlabToken]))

              createMultibranchPipelineJob(project, gitPath)
              addJobToQueue(project)

          }
          catch(Exception e) {
                  println e
                  print "skipping project ${project} because it has no Jenkinsfile\n\n"
          }
      }
  } catch(Exception e) {
      print "\n\n Please make sure you have set  GITLAB_HOST, GITLAB_TOKEN and GITLAB_GROUP_NAME in your deploy config for Jenkins \n\n\n"
      throw e
  }
} else if (githubAccount) {
  try {
      def projects = new groovy.json.JsonSlurper().parse(githubProjects.newReader(requestProperties: ['Authorization': "token ${githubToken}"]))

      projects.each {
          def project = it.name
          def gitPath = it.clone_url

          if (it.archived) {
              print "skipping project ${project} because it has been archived\n\n"
              return
          }

          try {
            // https://api.github.com/repos/$ORG or $USER/name/contents/Jenkinsfile
              def filesApi = new URL("${githubHost}/repos/${githubAccount}/${project}/contents/Jenkinsfile")
              def files = new groovy.json.JsonSlurper().parse(filesApi.newReader(requestProperties: ['Authorization': "token ${githubToken}"]))


              createMultibranchPipelineJob(project, gitPath)
              addJobToQueue(project)
          }
          catch(Exception e) {
                  println e
                  print "skipping project ${project} because it has no Jenkinsfile\n\n"
          }
      }
  } catch(Exception e) {
      print "\n\n Oops! something went wrong..... Try setting the GITHUB_* Env Vars \n\n\n"
      throw e
  }
} else {
    print "\n\n No tokens set in the Environment eg GITHUB* or GITLAB* so not sure what to do ..... 🤷‍♂️ \n\n\n"
}
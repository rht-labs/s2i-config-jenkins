import jenkins.model.Jenkins
import java.util.logging.Level
import java.util.logging.Logger

def slackBaseUrl = System.getenv('SLACK_BASE_URL')
final def LOG = Logger.getLogger("LABS")

if(slackBaseUrl != null) {

  LOG.log(Level.INFO,  'Configuring slack...' )

  def slackRoom = System.getenv('SLACK_ROOM')
  def slackTeamDomain = System.getenv('SLACK_TEAM')
  def slackTokenCredentialId = System.getenv('SLACK_TOKEN_CREDENTIAL_ID')

  def slack = Jenkins.instance.getDescriptorByType(jenkins.plugins.slack.SlackNotifier.DescriptorImpl)

  slack.baseUrl = slackBaseUrl
  slack.teamDomain = slackTeamDomain ?: ''
  slack.tokenCredentialId = slackTokenCredentialId ? System.getenv('OPENSHIFT_BUILD_NAMESPACE') + "-" + slackTokenCredentialId : ''
  slack.room = slackRoom ?: '#jenkins'
  slack.save()

  LOG.log(Level.INFO,  'Configured slack' )

}


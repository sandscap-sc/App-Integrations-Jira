/**
 * Copyright 2016-2017 Symphony Integrations - Symphony LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.symphonyoss.integration.jira.authorization;

import com.google.api.client.http.HttpMethods;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpStatusCodes;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.symphonyoss.integration.authorization.AuthorizationException;
import org.symphonyoss.integration.authorization.AuthorizationRepositoryService;
import org.symphonyoss.integration.authorization.UserAuthorizationData;
import org.symphonyoss.integration.authorization.oauth.OAuthRsaSignerFactory;
import org.symphonyoss.integration.authorization.oauth.v1.OAuth1Exception;
import org.symphonyoss.integration.exception.IntegrationRuntimeException;
import org.symphonyoss.integration.jira.authorization.oauth.v1.JiraOAuth1Data;
import org.symphonyoss.integration.jira.authorization.oauth.v1.JiraOAuth1Exception;
import org.symphonyoss.integration.jira.authorization.oauth.v1.JiraOAuth1Provider;
import org.symphonyoss.integration.json.JsonUtils;
import org.symphonyoss.integration.logging.LogMessageSource;
import org.symphonyoss.integration.model.yaml.AppAuthorizationModel;
import org.symphonyoss.integration.exception.bootstrap.CertificateNotFoundException;
import org.symphonyoss.integration.model.config.IntegrationSettings;
import org.symphonyoss.integration.model.yaml.Application;
import org.symphonyoss.integration.model.yaml.IntegrationProperties;
import org.symphonyoss.integration.utils.IntegrationUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service component responsible to provide the authentication properties from JIRA application.
 *
 * This component reads the YAML configuration file to retrieve application name and application
 * URL. It should also read the application public key configured on the filesystem and validate it.
 *
 * Created by rsanchez on 24/07/17.
 */
@Component
public class JiraAuthorizationManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(JiraAuthorizationManager.class);

  private static final String COMPONENT = "JIRA Authentication Manager";

  public static final String PRIVATE_KEY_FILENAME = "privateKeyFilename";

  public static final String PRIVATE_KEY_FILENAME_TEMPLATE = "%s_app.pkcs8";

  public static final String PUBLIC_KEY_FILENAME = "publicKeyFilename";

  public static final String PUBLIC_KEY_FILENAME_TEMPLATE = "%s_app_pub.pem";

  public static final String PUBLIC_KEY = "publicKey";

  public static final String CONSUMER_KEY = "consumerKey";

  private static final String PUBLIC_KEY_PREFIX = "-----BEGIN PUBLIC KEY-----\n";

  private static final String PUBLIC_KEY_SUFFIX = "-----END PUBLIC KEY-----\n";

  private static final String PRIVATE_KEY_PREFIX = "-----BEGIN PRIVATE KEY-----\n";

  private static final String PRIVATE_KEY_SUFFIX = "-----END PRIVATE KEY-----\n";

  private static final String AUTH_CALLBACK_PATH = "/v1/application/%s/authorization/authorize";

  @Autowired
  private LogMessageSource logMessage;

  @Autowired
  private IntegrationProperties properties;

  @Autowired
  private IntegrationUtils utils;

  @Autowired
  private OAuthRsaSignerFactory oAuthRsaSignerFactory;

  @Autowired
  private AuthorizationRepositoryService authRepoService;

  @Autowired
  private ApplicationContext context;

  /**
   * Provide the authorization properties for JIRA application.
   *
   * @param settings Integration settings
   * @return authorization properties
   */
  public AppAuthorizationModel getAuthorizationModel(IntegrationSettings settings) {
    String appType = settings.getType();
    Application application = properties.getApplication(appType);

    AppAuthorizationModel auth = application.getAuthorization();

    String publicKey = getPublicKey(auth, application);
    auth.getProperties().put(PUBLIC_KEY, publicKey);

    return auth;
  }

  /**
   * Read the application public key configured on the filesystem and validate it.
   *
   * @param authModel authorization properties
   * @param application Application settings
   * @return Application public key
   */
  private String getPublicKey(AppAuthorizationModel authModel, Application application) {
    String filename = getPublicKeyFilename(authModel, application);
    String publicKey = readKey(filename);

    if (StringUtils.isEmpty(publicKey)) {
      return null;
    }

    String pkAsString = publicKey.replace(PUBLIC_KEY_PREFIX, StringUtils.EMPTY)
        .replace(PUBLIC_KEY_SUFFIX, StringUtils.EMPTY);

    try {
      PublicKey pk = oAuthRsaSignerFactory.getPublicKey(pkAsString);
      if (pk != null) {
        return pkAsString;
      }
    } catch (OAuth1Exception e) {
      throw new IntegrationRuntimeException(COMPONENT,
          logMessage.getMessage("integration.jira.public.key.validation"), e);
    }

    LOGGER.warn("Application public key is invalid, please check the file {}", filename);
    return null;
  }

  /**
   * Retrieve the application public key filename.
   *
   * @param authModel authorization properties
   * @param application Application settings
   * @return Application public key filename
   */
  private String getPublicKeyFilename(AppAuthorizationModel authModel, Application application) {
    String fileName = (String) authModel.getProperties().get(PUBLIC_KEY_FILENAME);

    if (StringUtils.isEmpty(fileName)) {
      return String.format(PUBLIC_KEY_FILENAME_TEMPLATE, application.getId());
    }
    return fileName;
  }

  /**
   * Read the application private key configured on the filesystem and validate it.
   *
   * @param settings This integration settings.
   * @return Application private key
   */
  private String getPrivateKey(IntegrationSettings settings) {
    String appType = settings.getType();
    Application application = properties.getApplication(appType);
    AppAuthorizationModel authModel = application.getAuthorization();

    String filename = getPrivateKeyFilename(authModel, application);
    String privateKey = readKey(filename);

    if (StringUtils.isEmpty(privateKey)) {
      return null;
    }

    String pkAsString = privateKey.replace(PRIVATE_KEY_PREFIX, StringUtils.EMPTY)
        .replace(PRIVATE_KEY_SUFFIX, StringUtils.EMPTY);

    try {
      PrivateKey pk = oAuthRsaSignerFactory.getPrivateKey(pkAsString);
      if (pk != null) {
        return pkAsString;
      }
    } catch (OAuth1Exception e) {
      throw new IntegrationRuntimeException(COMPONENT,
          logMessage.getMessage("integration.jira.private.key.validation"), e);
    }

    LOGGER.warn("Application private key is invalid, please check the file {}", filename);
    return null;
  }

  /**
   * Retrieve the application private key filename.
   *
   * @param authModel authorization properties
   * @param application Application settings
   * @return Application private key filename
   */
  private String getPrivateKeyFilename(AppAuthorizationModel authModel, Application application) {
    String fileName = (String) authModel.getProperties().get(PRIVATE_KEY_FILENAME);
    if (StringUtils.isEmpty(fileName)) {
      return String.format(PRIVATE_KEY_FILENAME_TEMPLATE, application.getId());
    }
    return fileName;
  }

  /**
   * Read public or private key configured on the filesystem.
   *
   * @param fileName Public/private key filename
   * @return Application public/private key or null if the file not found
   */
  private String readKey(String fileName) {
    try {
      String certsDir = utils.getCertsDirectory();
      Path keyPath = Paths.get(certsDir + fileName);

      if (Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS)) {
        byte[] pubKeyBytes = Files.readAllBytes(keyPath);
        return new String(pubKeyBytes);
      }

      LOGGER.error("Cannot read the key. Make sure the file {} already exists",
          keyPath.toAbsolutePath());
    } catch (IOException e) {
      LOGGER.error("Cannot read the file " + fileName + ". Please check the file permissions", e);
    } catch (CertificateNotFoundException e) {
      LOGGER.error(
          "Cannot find the certificate directory. Please make sure this directory was already "
              + "created properly");
    }

    return null;
  }

  /**
   * Build and return a callback URL to be used when constructing the JiraOauth1Provider.
   * @param settings Jira integration settings.
   * @return Built URL.
   */
  private String getCallbackUrl(IntegrationSettings settings) {
    String callbackUrl = String.format(AUTH_CALLBACK_PATH, settings.getConfigurationId());
    return properties.getIntegrationBridgeUrl() + callbackUrl;
  }

  /**
   * Verify if the passed user has authorized us to perform Jira API calls on behalf of him/her.
   * @param settings Jira integration settings.
   * @param url Jira base URL.
   * @param userId Symphony user ID.
   * @return <code>true</code> If the passed user has authorized the access.
   * @throws AuthorizationException Thrown in case of error.
   */
  public boolean isUserAuthorized(IntegrationSettings settings, String url, Long userId)
      throws AuthorizationException {
    UserAuthorizationData u =
        authRepoService.find(settings.getType(), settings.getConfigurationId(), url, userId);

    if ((u == null) || (u.getData() == null)) {
      return false;
    }

    JiraOAuth1Data jiraOAuth1Data;

    try {
      jiraOAuth1Data = JsonUtils.readValue(u.getData(), JiraOAuth1Data.class);
    } catch (IOException e) {
      throw new JiraOAuth1Exception("Invalid temporary token");
    }

    if (StringUtils.isEmpty(jiraOAuth1Data.getAccessToken())) {
      return false;
    }

    AppAuthorizationModel appAuthorizationModel = getAuthorizationModel(settings);
    String consumerKey = (String) appAuthorizationModel.getProperties().get(CONSUMER_KEY);
    String privateKey = getPrivateKey(settings);
    String callbackUrl = getCallbackUrl(settings);

    JiraOAuth1Provider provider = context.getBean(JiraOAuth1Provider.class);
    provider.configure(consumerKey, privateKey, url, callbackUrl);

    try {
      URL myselfUrl = new URL(url);
      myselfUrl = new URL(myselfUrl, "/rest/api/2/myself");
      HttpResponse response =
          provider.makeAuthorizedRequest(jiraOAuth1Data.getAccessToken(), myselfUrl,
              HttpMethods.GET, null);

      return response.getStatusCode() != HttpStatusCodes.STATUS_CODE_UNAUTHORIZED;
    } catch (MalformedURLException e) {
      throw new JiraOAuth1Exception(logMessage.getMessage("integration.jira.url.api.invalid", url),
          e, logMessage.getMessage("integration.jira.url.api.invalid.solution"));
    }
  }

  /**
   * Return an URL to allow the user to authorize us to perform Jira API calls on behalf of him/her.
   * @param settings Jira integration settings.
   * @param url Jira base URL.
   * @param userId Symphony user ID.
   * @return Authorization URL.
   * @throws AuthorizationException Thrown in case of error.
   */
  public String getAuthorizationUrl(IntegrationSettings settings, String url, Long userId)
      throws AuthorizationException {
    AppAuthorizationModel appAuthorizationModel = getAuthorizationModel(settings);
    String consumerKey = (String) appAuthorizationModel.getProperties().get(CONSUMER_KEY);
    String privateKey = getPrivateKey(settings);
    String callbackUrl = getCallbackUrl(settings);

    JiraOAuth1Provider provider = context.getBean(JiraOAuth1Provider.class);
    provider.configure(consumerKey, privateKey, url, callbackUrl);

    String temporaryToken = provider.requestTemporaryToken();
    String authorizationUrl = provider.requestAuthorizationUrl(temporaryToken);

    JiraOAuth1Data jiraOAuth1Data = new JiraOAuth1Data(temporaryToken);
    UserAuthorizationData userAuthData = new UserAuthorizationData(url, userId, jiraOAuth1Data);

    authRepoService.save(settings.getType(), settings.getConfigurationId(), userAuthData);

    return authorizationUrl;
  }

  /**
   * Authorize a temporaty token by getting a permanent access token and saving it.
   * @param settings JIRA integration settings.
   * @param temporaryToken The original temporary token used to get the authorization from a user.
   * @param verifierCode The granted access code created when a user allow our application.
   * @throws AuthorizationException Thrown when there is a problem in this operation.
   */
  public void authorizeTemporaryToken(IntegrationSettings settings, String temporaryToken,
      String verifierCode) throws AuthorizationException {

    Map<String, String> filter = new HashMap<>();
    filter.put("temporaryToken", temporaryToken);
    List<UserAuthorizationData> result =
        authRepoService.search(settings.getType(), settings.getConfigurationId(), filter);

    if (result.isEmpty()) {
      throw new JiraOAuth1Exception(
          logMessage.getMessage("integration.jira.auth.user.data.not.found", temporaryToken),
          logMessage.getMessage("integration.jira.auth.user.data.not.found.solution"));
    }
    UserAuthorizationData userAuthData = result.get(0);

    AppAuthorizationModel appAuthorizationModel = getAuthorizationModel(settings);
    String consumerKey = (String) appAuthorizationModel.getProperties().get(CONSUMER_KEY);
    String privateKey = getPrivateKey(settings);
    String callbackUrl = getCallbackUrl(settings);

    JiraOAuth1Provider provider = context.getBean(JiraOAuth1Provider.class);
    provider.configure(consumerKey, privateKey, userAuthData.getUrl(), callbackUrl);

    String accessCode = provider.requestAcessToken(temporaryToken, verifierCode);
    JiraOAuth1Data jiraOAuth1Data = new JiraOAuth1Data(temporaryToken, accessCode);
    userAuthData.setData(jiraOAuth1Data);

    authRepoService.save(settings.getType(), settings.getConfigurationId(), userAuthData);
  }
}

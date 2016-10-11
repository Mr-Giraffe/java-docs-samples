/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.appengine.firetactoe;

import com.google.api.client.extensions.appengine.http.UrlFetchTransport;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.appengine.api.appidentity.AppIdentityService;
import com.google.appengine.api.appidentity.AppIdentityServiceFactory;
import com.google.common.io.BaseEncoding;
import com.google.common.io.CharStreams;

import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class FirebaseChannel {
  private static final String FIREBASE_SNIPPET_PATH = "WEB-INF/view/firebase_config.jspf";
  private static final Collection FIREBASE_SCOPES = Arrays.asList(
      "https://www.googleapis.com/auth/firebase.database",
      "https://www.googleapis.com/auth/userinfo.email"
  );
  private static final String IDENTITY_ENDPOINT =
      "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit";
  static final HttpTransport HTTP_TRANSPORT = new UrlFetchTransport();

  private String firebaseDbUrl;
  private GoogleCredential credential;

  private static FirebaseChannel instance;

  public static FirebaseChannel getInstance() {
    if (instance == null) {
      instance = new FirebaseChannel();
    }
    return instance;
  }

  private FirebaseChannel() {
    try {
      String firebaseSnippet = CharStreams.toString(
          new InputStreamReader(new FileInputStream(FIREBASE_SNIPPET_PATH)));
      firebaseDbUrl = parseFirebaseUrl(firebaseSnippet);

      credential = GoogleCredential.getApplicationDefault().createScoped(FIREBASE_SCOPES);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String parseFirebaseUrl(String firebaseSnippet) {
    int idx = firebaseSnippet.indexOf("databaseURL");
    if (-1 == idx) {
      throw new RuntimeException(
          "Please copy your Firebase web snippet into " + FIREBASE_SNIPPET_PATH);
    }
    idx = firebaseSnippet.indexOf(':', idx);
    int openQuote = firebaseSnippet.indexOf('"', idx);
    int closeQuote = firebaseSnippet.indexOf('"', openQuote + 1);
    return firebaseSnippet.substring(openQuote + 1, closeQuote);
  }

  public void sendFirebaseMessage(String channelKey, Game game)
      throws IOException {
    // Make requests auth'ed using Application Default Credentials
    HttpRequestFactory requestFactory = HTTP_TRANSPORT.createRequestFactory(credential);
    GenericUrl url = new GenericUrl(
        String.format("%s/channels/%s.json", firebaseDbUrl, channelKey));
    HttpResponse response = null;

    try {
      if (null == game) {
        response = requestFactory.buildDeleteRequest(url).execute();
      } else {
        String gameJson = game.getMessageString();
        response = requestFactory.buildPatchRequest(
            url, new ByteArrayContent("application/json", gameJson.getBytes())).execute();
      }

      if (response.getStatusCode() != 200) {
        throw new RuntimeException(
            "Error code while updating Firebase: " + response.getStatusCode());
      }

    } finally {
      if (null != response) {
        response.disconnect();
      }
    }
  }

  /**
   * Create a secure JWT token for the given userId.
   */
  public String createFirebaseToken(Game game, String userId) {
    final AppIdentityService appIdentity = AppIdentityServiceFactory.getAppIdentityService();
    final BaseEncoding base64 = BaseEncoding.base64();

    String header = base64.encode("{\"typ\":\"JWT\",\"alg\":\"RS256\"}".getBytes());

    // Construct the claim
    String channelKey = game.getChannelKey(userId);
    String clientEmail = appIdentity.getServiceAccountName();
    long epochTime = System.currentTimeMillis() / 1000;
    long expire = epochTime + 60 * 60; // an hour from now

    Map<String, Object> claims = new HashMap<String, Object>();
    claims.put("iss", clientEmail);
    claims.put("sub", clientEmail);
    claims.put("aud", IDENTITY_ENDPOINT);
    claims.put("uid", channelKey);
    claims.put("iat", epochTime);
    claims.put("exp", expire);

    String payload = base64.encode(new JSONObject(claims).toString().getBytes());
    String toSign = String.format("%s.%s", header, payload);
    AppIdentityService.SigningResult result = appIdentity.signForApp(toSign.getBytes());
    return String.format("%s.%s", toSign, base64.encode(result.getSignature()));
  }
}

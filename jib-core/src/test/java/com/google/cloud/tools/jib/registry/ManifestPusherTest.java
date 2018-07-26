/*
 * Copyright 2018 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.registry;

import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.cloud.tools.jib.http.BlobHttpContent;
import com.google.cloud.tools.jib.http.Response;
import com.google.cloud.tools.jib.image.json.V22ManifestTemplate;
import com.google.cloud.tools.jib.json.JsonTemplateMapper;
import com.google.common.io.Resources;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

/** Tests for {@link ManifestPusher}. */
@RunWith(MockitoJUnitRunner.class)
public class ManifestPusherTest {

  private Path v22manifestJsonFile;
  private V22ManifestTemplate fakeManifestTemplate;
  private ManifestPusher testManifestPusher;

  @Before
  public void setUp() throws URISyntaxException, IOException {
    v22manifestJsonFile = Paths.get(Resources.getResource("json/v22manifest.json").toURI());
    fakeManifestTemplate =
        JsonTemplateMapper.readJsonFromFile(v22manifestJsonFile, V22ManifestTemplate.class);

    testManifestPusher =
        new ManifestPusher(
            new RegistryEndpointRequestProperties("someServerUrl", "someImageName"),
            fakeManifestTemplate,
            "test-image-tag");
  }

  @Test
  public void testStatusCodeInvalidMediaType() {
    Assert.assertEquals(415, ManifestPusher.STATUS_CODE_INVALID_MEDIA_TYPE);
  }

  @Test
  public void testGetContent() throws IOException {
    BlobHttpContent body = testManifestPusher.getContent();

    Assert.assertNotNull(body);
    Assert.assertEquals(V22ManifestTemplate.MANIFEST_MEDIA_TYPE, body.getType());

    ByteArrayOutputStream bodyCaptureStream = new ByteArrayOutputStream();
    body.writeTo(bodyCaptureStream);
    String v22manifestJson =
        new String(Files.readAllBytes(v22manifestJsonFile), StandardCharsets.UTF_8);
    Assert.assertEquals(
        v22manifestJson, new String(bodyCaptureStream.toByteArray(), StandardCharsets.UTF_8));
  }

  @Test
  public void testHandleResponse() {
    Assert.assertNull(testManifestPusher.handleResponse(Mockito.mock(Response.class)));
  }

  @Test
  public void testHandleHttpResponseException_notHandled()
      throws HttpResponseException, RegistryErrorException {
    HttpResponseException httpException = Mockito.mock(HttpResponseException.class);
    Mockito.when(httpException.getStatusCode()).thenReturn(HttpStatusCodes.STATUS_CODE_CONFLICT);
    Assert.assertNull(testManifestPusher.handleHttpResponseException(httpException));
  }
  
  @Test
  public void testHandleHttpResponseException_handled() throws HttpResponseException {
    verifyHttpResponseExceptionMapping(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
    verifyHttpResponseExceptionMapping(HttpStatusCodes.STATUS_CODE_NOT_FOUND);
    verifyHttpResponseExceptionMapping(HttpStatusCodes.STATUS_CODE_METHOD_NOT_ALLOWED);
    verifyHttpResponseExceptionMapping(ManifestPusher.STATUS_CODE_INVALID_MEDIA_TYPE);
  }

  private void verifyHttpResponseExceptionMapping(int statusCode) throws HttpResponseException {
    HttpResponseException httpException = Mockito.mock(HttpResponseException.class);
    Mockito.when(httpException.getStatusCode()).thenReturn(statusCode);
    Mockito.when(httpException.getContent())
        .thenReturn(
            "{\"errors\":[{\"code\":\"MANIFEST_INVALID\",\"message\":\"manifest invalid\"}]}");
    try {
      testManifestPusher.handleHttpResponseException(httpException);
      Assert.fail();
    } catch (RegistryErrorException registryException) {
      Assert.assertThat(
          registryException.getMessage(),
          CoreMatchers.startsWith(
              "Tried to push image manifest for someServerUrl/someImageName:test-image-tag but failed because: manifest invalid (something went wrong)"));
      Assert.assertSame(httpException, registryException.getCause());
    }
  }
  
  @Test
  public void testApiRoute() throws MalformedURLException {
    Assert.assertEquals(
        new URL("http://someApiBase/someImageName/manifests/test-image-tag"),
        testManifestPusher.getApiRoute("http://someApiBase/"));
  }

  @Test
  public void testGetHttpMethod() {
    Assert.assertEquals("PUT", testManifestPusher.getHttpMethod());
  }

  @Test
  public void testGetActionDescription() {
    Assert.assertEquals(
        "push image manifest for someServerUrl/someImageName:test-image-tag",
        testManifestPusher.getActionDescription());
  }

  @Test
  public void testGetAccept() {
    Assert.assertEquals(0, testManifestPusher.getAccept().size());
  }
}

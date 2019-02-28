package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.KeelApplication
import com.netflix.spinnaker.keel.api.*
import com.netflix.spinnaker.keel.events.ResourceEvent
import com.netflix.spinnaker.keel.events.ResourceEventType.DELETE
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.redis.spring.EmbeddedRedisConfiguration
import com.netflix.spinnaker.keel.redis.spring.MockEurekaConfig
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML_VALUE
import com.nhaarman.mockito_kotlin.*
import org.junit.Test
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders.CONTENT_TYPE
import org.springframework.http.MediaType
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.context.junit4.SpringRunner
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import strikt.api.expectThat
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import java.util.*

@RunWith(SpringRunner::class)
@SpringBootTest(
  classes = [MockConfiguration::class, KeelApplication::class, MockEurekaConfig::class, EmbeddedRedisConfiguration::class],
  properties = [
    "clouddriver.baseUrl=https://localhost:8081",
    "orca.baseUrl=https://localhost:8082"
  ],
  webEnvironment = RANDOM_PORT
)
@AutoConfigureMockMvc
internal class ResourceControllerTest {
  @Autowired
  lateinit var mvc: MockMvc

  @Autowired
  lateinit var resourceRepository: ResourceRepository

  @Autowired
  lateinit var resourcePersister: ResourcePersister

  var mockResource = Resource(
    apiVersion = ApiVersion("ec2.spinnaker.netflix.com/v1"),
    kind = "securityGroup",
    metadata = ResourceMetadata(ResourceName("ec2:securityGroup:test:us-west-2:keel")),
    spec = "mockingThis"
  )

  @Test
  fun `can create a resource as YAML`() {
    whenever(resourcePersister.handle(any())) doReturn mockResource

    val request = post("/resources")
      .accept(APPLICATION_YAML)
      .contentType(APPLICATION_YAML)
      .content(
        """---
          |apiVersion: ec2.spinnaker.netflix.com/v1
          |kind: securityGroup
          |spec:
          |  account: test
          |  region: us-west-2
          |  name: keel"""
          .trimMargin()
      )
    mvc
      .perform(request)
      .andExpect(status().isOk)
  }

  @Test
  fun `can create a resource as JSON`() {
    whenever(resourcePersister.handle(any())) doReturn mockResource

    val request = post("/resources")
      .accept(APPLICATION_JSON)
      .contentType(APPLICATION_JSON)
      .content(
        """{
          |  "apiVersion": "ec2.spinnaker.netflix.com/v1",
          |  "kind": "securityGroup",
          |  "spec": {
          |    "account": "test",
          |    "region": "us-west-2",
          |    "name": "keel"
          |  }
          |}"""
          .trimMargin()
      )
    mvc
      .perform(request)
      .andExpect(status().isOk)
  }

  @Test
  fun `can get a resource as YAML`() {
    val resource = Resource(
      apiVersion = SPINNAKER_API_V1,
      kind = "whatever",
      metadata = ResourceMetadata(
        name = ResourceName("my-resource"),
        uid = UUID.randomUUID(),
        resourceVersion = 1234L
      ),
      spec = "some spec content"
    )
    resourceRepository.store(resource)

    val request = get("/resources/${resource.metadata.name}")
      .accept(APPLICATION_YAML)
    val result = mvc
      .perform(request)
      .andExpect(status().isOk)
      .andReturn()
    expectThat(result.response.getHeaderValue(CONTENT_TYPE))
      .isA<String>()
      .get { MediaType.parseMediaType(this) }
      .get { "$type/$subtype" }
      .isEqualTo(APPLICATION_YAML_VALUE)
  }

  @Test
  fun `can delete a resource`() {
    whenever(resourcePersister.handle(any())) doReturn mockResource

    val resource = Resource(
      apiVersion = SPINNAKER_API_V1,
      kind = "securityGroup",
      metadata = ResourceMetadata(
        name = ResourceName("my-resource"),
        uid = UUID.randomUUID(),
        resourceVersion = 1234L
      ),
      spec = "some spec content"
    )
    resourceRepository.store(resource)

    val request = delete("/resources/${resource.metadata.name}")
      .accept(APPLICATION_YAML)
    mvc
      .perform(request)
      .andExpect(status().isOk)

    verify(resourcePersister).handle(ResourceEvent(DELETE, resource))

    //clean up after the test
    resourceRepository.delete(resource.metadata.name)
  }

  @Test
  fun `unknown resource name results in a 404`() {
    val request = get("/resources/i-do-not-exist")
      .accept(APPLICATION_YAML)
    mvc
      .perform(request)
      .andExpect(status().isNotFound)
  }
}

@Configuration
class MockConfiguration {
  @Primary
  @Bean fun resourcePersister() = mock<ResourcePersister>()
}
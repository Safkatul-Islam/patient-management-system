package org.pms.patientservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Fix 4: paths were a mix of /patient, /patients and a bare /{id}. All operations now live under
 * the plural collection path.
 *
 * <p>Security denies every path it does not list, so an old path now answers 403 even for an ADMIN
 * before routing is reached. Each old-path test therefore also asks the MVC handler mapping
 * directly that nothing is mapped there.
 */
class ResourcePathTest extends AbstractPatientApiTest {

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  @Test
  @DisplayName("all operations are served under /api/v1/patients")
  void allOperationsUsePluralPath() throws Exception {
    mockMvc.perform(get(PATIENTS).with(asAdmin())).andExpect(status().isOk());

    String id = createPatient(uniqueEmail());

    mockMvc.perform(get(PATIENTS + "/" + id).with(asAdmin())).andExpect(status().isOk());

    mockMvc
        .perform(
            put(PATIENTS + "/" + id)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "1990-01-01", null)))
        .andExpect(status().isOk());

    mockMvc.perform(delete(PATIENTS + "/" + id).with(asAdmin())).andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("the current paths are routed (control for the old-path checks)")
  void currentPathsAreRouted() throws Exception {
    assertThat(isRouted(HttpMethod.POST, PATIENTS)).isTrue();
    assertThat(isRouted(HttpMethod.GET, PATIENTS + "/" + UUID.randomUUID())).isTrue();
    assertThat(isRouted(HttpMethod.PUT, PATIENTS + "/" + UUID.randomUUID())).isTrue();
    assertThat(isRouted(HttpMethod.DELETE, PATIENTS + "/" + UUID.randomUUID())).isTrue();
  }

  @Test
  @DisplayName("the old singular create path /api/v1/patient is gone")
  void oldSingularCreatePathIsGone() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/patient")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "1990-01-01", "2024-01-01")))
        .andExpect(status().isForbidden());

    assertThat(isRouted(HttpMethod.POST, "/api/v1/patient")).isFalse();
  }

  @Test
  @DisplayName("the old bare-id update path /api/v1/{id} is gone")
  void oldBareIdUpdatePathIsGone() throws Exception {
    // An existing id, so the path itself is the only reason nothing is served.
    String id = createPatient(uniqueEmail());

    mockMvc
        .perform(
            put("/api/v1/" + id)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(patientJson(uniqueEmail(), "1990-01-01", null)))
        .andExpect(status().isForbidden());

    assertThat(isRouted(HttpMethod.PUT, "/api/v1/" + id)).isFalse();
  }

  @Test
  @DisplayName("the old singular delete path /api/v1/patient/{id} is gone")
  void oldSingularDeletePathIsGone() throws Exception {
    String id = createPatient(uniqueEmail());

    mockMvc
        .perform(delete("/api/v1/patient/" + id).with(asAdmin()))
        .andExpect(status().isForbidden());

    assertThat(isRouted(HttpMethod.DELETE, "/api/v1/patient/" + id)).isFalse();
  }

  /** Whether any controller method is mapped to this method and path. */
  private boolean isRouted(HttpMethod method, String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method.name(), path);
    ServletRequestPathUtils.parseAndCache(request);
    return handlerMapping.getHandler(request) != null;
  }
}

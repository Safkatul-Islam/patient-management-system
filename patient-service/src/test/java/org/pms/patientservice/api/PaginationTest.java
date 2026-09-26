package org.pms.patientservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.patientservice.repository.PatientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * GET /api/v1/patients is paged and returns Spring Data's stable {@code {content, page}} shape.
 * Other tests share the database, so totals are compared with the live row count.
 */
class PaginationTest extends AbstractPatientApiTest {

  @Autowired private PatientRepository patientRepository;

  @Test
  @DisplayName("list returns a page of content plus page metadata")
  void listReturnsPageShape() throws Exception {
    createPatient(uniqueEmail());
    createPatient(uniqueEmail());
    createPatient(uniqueEmail());
    long total = patientRepository.count();

    mockMvc
        .perform(get(PATIENTS).param("page", "1").param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.page.size").value(2))
        .andExpect(jsonPath("$.page.number").value(1))
        .andExpect(jsonPath("$.page.totalElements").value(total))
        .andExpect(jsonPath("$.page.totalPages").value((total + 1) / 2));
  }

  @Test
  @DisplayName("page size defaults to 20")
  void defaultPageSizeIs20() throws Exception {
    mockMvc
        .perform(get(PATIENTS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.size").value(20))
        .andExpect(jsonPath("$.page.number").value(0));
  }

  @Test
  @DisplayName("a page size above the maximum is capped at 100")
  void pageSizeIsCappedAt100() throws Exception {
    mockMvc
        .perform(get(PATIENTS).param("size", "500"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.size").value(100));
  }

  @Test
  @DisplayName("unsorted requests are ordered by name")
  void defaultSortIsByName() throws Exception {
    // Every other test patient is named "Regression Patient", which sorts after this one.
    mockMvc
        .perform(
            post(PATIENTS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Aaron Aardvark","email":"%s","address":"1 Sort St",\
                    "dateOfBirth":"1990-01-01","registeredDate":"2024-01-01"}"""
                        .formatted(uniqueEmail())))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get(PATIENTS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Aaron Aardvark"));
  }

  @Test
  @DisplayName("an allowed sort property and direction are applied")
  void explicitSortIsApplied() throws Exception {
    createPatient(uniqueEmail());

    String response =
        mockMvc
            .perform(get(PATIENTS).param("sort", "dateOfBirth,desc").param("size", "100"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // ISO-8601 dates sort chronologically as strings.
    List<String> datesOfBirth = JsonPath.read(response, "$.content[*].dateOfBirth");
    assertThat(datesOfBirth).isNotEmpty().isSortedAccordingTo(Comparator.reverseOrder());
  }

  @Test
  @DisplayName("sorting by a property outside the allow-list is a 400 problem, not a 500")
  void sortOutsideAllowListIs400() throws Exception {
    mockMvc
        .perform(get(PATIENTS).param("sort", "address"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid sort parameter"));
  }

  @Test
  @DisplayName("sorting by a property the entity does not have is a 400 problem, not a 500")
  void sortByUnknownPropertyIs400() throws Exception {
    mockMvc
        .perform(get(PATIENTS).param("sort", "doesNotExist,asc"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid sort parameter"));
  }
}

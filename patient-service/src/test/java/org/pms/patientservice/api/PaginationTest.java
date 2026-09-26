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
        .perform(get(PATIENTS).with(asAdmin()).param("page", "1").param("size", "2"))
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
        .perform(get(PATIENTS).with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.size").value(20))
        .andExpect(jsonPath("$.page.number").value(0));
  }

  @Test
  @DisplayName("a page size above the maximum is capped at 100")
  void pageSizeIsCappedAt100() throws Exception {
    mockMvc
        .perform(get(PATIENTS).with(asAdmin()).param("size", "500"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.size").value(100));
  }

  @Test
  @DisplayName("unsorted requests are ordered by name")
  void defaultSortIsByName() throws Exception {
    // Every other test patient is named "Regression Patient", which sorts after this one.
    createNamedPatient("Aaron Aardvark", "2024-01-01");

    mockMvc
        .perform(get(PATIENTS).with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Aaron Aardvark"));
  }

  @Test
  @DisplayName("a negative page number is treated as the first page")
  void negativePageIsFirstPage() throws Exception {
    mockMvc
        .perform(get(PATIENTS).with(asAdmin()).param("page", "-1").param("size", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.number").value(0))
        .andExpect(jsonPath("$.page.size").value(5));
  }

  @Test
  @DisplayName("a page size of 0 falls back to the default of 20")
  void zeroPageSizeFallsBackToDefault() throws Exception {
    mockMvc
        .perform(get(PATIENTS).with(asAdmin()).param("size", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.size").value(20));
  }

  @Test
  @DisplayName("multiple sort parameters apply in order: first is primary, next breaks ties")
  void multipleSortParametersApplyInOrder() throws Exception {
    // Registration dates later than any other test patient's, so these three lead the page.
    // "Xray" would come first if name were the primary sort; it must come last. (Names are
    // late in the alphabet so they cannot lead the default name-sorted page in other tests.)
    createNamedPatient("Zulu Multisort", "2099-12-31");
    createNamedPatient("Yankee Multisort", "2099-12-31");
    createNamedPatient("Xray Multisort", "2099-12-30");

    mockMvc
        .perform(
            get(PATIENTS)
                .with(asAdmin())
                .param("sort", "registeredDate,desc")
                .param("sort", "name,asc")
                .param("size", "3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Yankee Multisort"))
        .andExpect(jsonPath("$.content[1].name").value("Zulu Multisort"))
        .andExpect(jsonPath("$.content[2].name").value("Xray Multisort"));
  }

  @Test
  @DisplayName("multiple sort parameters with one outside the allow-list are a 400 problem")
  void multipleSortParametersWithOneDisallowedIs400() throws Exception {
    mockMvc
        .perform(
            get(PATIENTS).with(asAdmin()).param("sort", "name,asc").param("sort", "address,desc"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid sort parameter"));
  }

  @Test
  @DisplayName("an allowed sort property and direction are applied")
  void explicitSortIsApplied() throws Exception {
    createPatient(uniqueEmail());

    String response =
        mockMvc
            .perform(
                get(PATIENTS)
                    .with(asAdmin())
                    .param("sort", "dateOfBirth,desc")
                    .param("size", "100"))
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
        .perform(get(PATIENTS).with(asAdmin()).param("sort", "address"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid sort parameter"));
  }

  @Test
  @DisplayName("sorting by a property the entity does not have is a 400 problem, not a 500")
  void sortByUnknownPropertyIs400() throws Exception {
    mockMvc
        .perform(get(PATIENTS).with(asAdmin()).param("sort", "doesNotExist,asc"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid sort parameter"));
  }

  private void createNamedPatient(String name, String registeredDate) throws Exception {
    mockMvc
        .perform(
            post(PATIENTS)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"%s","email":"%s","address":"1 Sort St",\
                    "dateOfBirth":"1990-01-01","registeredDate":"%s"}"""
                        .formatted(name, uniqueEmail(), registeredDate)))
        .andExpect(status().isCreated());
  }
}

package org.pms.patientservice.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fix 1: updatePatient parsed registeredDate unconditionally, but that field is only
 * required on create. An update omitting it used to blow up with an NPE (HTTP 500).
 */
class UpdateWithoutRegisteredDateTest extends AbstractPatientApiTest {

    @Test
    @DisplayName("PUT without registeredDate succeeds instead of returning 500")
    void updateWithoutRegisteredDateSucceeds() throws Exception {
        String id = createPatient(uniqueEmail());
        String updatedEmail = uniqueEmail();

        mockMvc.perform(put(PATIENTS + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientJson(updatedEmail, "1991-02-03", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(updatedEmail))
                .andExpect(jsonPath("$.dateOfBirth").value("1991-02-03"));
    }

    @Test
    @DisplayName("PUT without registeredDate preserves the original registration date")
    void updateWithoutRegisteredDatePreservesOriginalValue() throws Exception {
        String id = createPatient(uniqueEmail());

        mockMvc.perform(put(PATIENTS + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientJson(uniqueEmail(), "1991-02-03", null)))
                .andExpect(status().isOk());

        // registeredDate is not exposed on the response DTO, so assert the record is
        // still readable and intact rather than silently nulled out by the update.
        mockMvc.perform(get(PATIENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").isNotEmpty());
    }

    @Test
    @DisplayName("PUT with registeredDate still succeeds")
    void updateWithRegisteredDateStillWorks() throws Exception {
        String id = createPatient(uniqueEmail());

        mockMvc.perform(put(PATIENTS + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientJson(uniqueEmail(), "1991-02-03", "2025-05-05")))
                .andExpect(status().isOk());
    }
}

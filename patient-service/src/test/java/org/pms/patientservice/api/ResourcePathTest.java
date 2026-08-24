package org.pms.patientservice.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fix 4: paths were a mix of /patient, /patients and a bare /{id}. All four operations
 * now live under the plural collection path.
 */
class ResourcePathTest extends AbstractPatientApiTest {

    @Test
    @DisplayName("all four operations are served under /api/v1/patients")
    void allOperationsUsePluralPath() throws Exception {
        mockMvc.perform(get(PATIENTS)).andExpect(status().isOk());

        String id = createPatient(uniqueEmail());

        mockMvc.perform(put(PATIENTS + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientJson(uniqueEmail(), "1990-01-01", null)))
                .andExpect(status().isOk());

        mockMvc.perform(delete(PATIENTS + "/" + id))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("the old singular create path /api/v1/patient is gone")
    void oldSingularCreatePathIsGone() throws Exception {
        mockMvc.perform(post("/api/v1/patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientJson(uniqueEmail(), "1990-01-01", "2024-01-01")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the old bare-id update path /api/v1/{id} is gone")
    void oldBareIdUpdatePathIsGone() throws Exception {
        mockMvc.perform(put("/api/v1/" + SEEDED_PATIENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientJson(uniqueEmail(), "1990-01-01", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the old singular delete path /api/v1/patient/{id} is gone")
    void oldSingularDeletePathIsGone() throws Exception {
        mockMvc.perform(delete("/api/v1/patient/" + SEEDED_PATIENT_ID))
                .andExpect(status().isNotFound());
    }
}

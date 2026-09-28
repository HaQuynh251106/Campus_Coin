package com.campuscoin.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Show or withdraw an announcement (UC-21 B2).")
public record UpdateAnnouncementRequest(

        @Schema(description = "True shows the notice on every dashboard it is addressed to; false "
                + "withdraws it without deleting it. Whether it actually appears also depends on "
                + "its start and end times.", example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Choose whether the announcement is active: true or false.")
        Boolean isActive) {
}

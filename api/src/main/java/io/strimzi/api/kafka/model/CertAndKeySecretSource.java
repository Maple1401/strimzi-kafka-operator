/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;

/**
 * Represents a certificate and private key pair inside a Secret
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CertAndKeySecretSource extends CertSecretSource {
    private static final long serialVersionUID = 1L;

    protected String key;

    @Description("The name of the private key in the Secret.")
    @JsonProperty(required = true)
    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}

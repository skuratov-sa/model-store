package com.model_store.controller;

import com.model_store.model.base.Dictionary;
import com.model_store.model.constant.DictionaryType;
import com.model_store.service.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureWebTestClient
class DictionaryControllerWebTest extends IntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void catalogFlagsDictionaryContainsEverySupportedFlag() {
        var entries = webTestClient.get()
                .uri("/dictionary?type=CATALOG_FLAGS")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Dictionary.class)
                .returnResult()
                .getResponseBody();

        assertThat(entries).isNotNull();
        assertThat(entries).extracting(Dictionary::getType)
                .containsOnly(DictionaryType.CATALOG_FLAGS);
        assertThat(entries).extracting(Dictionary::getValue)
                .containsExactlyInAnyOrder("ALL", "PREORDER", "NON_PREORDER", "USED");
        assertThat(entries).allSatisfy(entry -> assertThat(entry.getDescription()).isNotBlank());
    }
}

/*
 * Copyright (C) 2023-2025 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.phase4.peppolstandalone.spi;

import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unece.cefact.namespaces.sbdh.StandardBusinessDocument;

import com.helger.commons.annotation.IsSPIImplementation;
import com.helger.commons.collection.impl.ICommonsList;
import com.helger.commons.http.HttpHeaderMap;
import com.helger.peppol.reporting.api.PeppolReportingItem;
import com.helger.peppol.reporting.api.backend.PeppolReportingBackend;
import com.helger.peppol.reporting.api.backend.PeppolReportingBackendException;
import com.helger.peppol.sbdh.PeppolSBDHData;
import com.helger.peppol.utils.PeppolCertificateHelper;
import com.helger.phase4.config.AS4Configuration;
import com.helger.phase4.ebms3header.Ebms3Error;
import com.helger.phase4.ebms3header.Ebms3UserMessage;
import com.helger.phase4.incoming.IAS4IncomingMessageMetadata;
import com.helger.phase4.incoming.IAS4IncomingMessageState;
import com.helger.phase4.peppol.servlet.IPhase4PeppolIncomingSBDHandlerSPI;
import com.helger.phase4.peppol.servlet.Phase4PeppolServletMessageProcessorSPI;
import com.helger.phase4.peppolstandalone.APConfig;

import java.util.Objects;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * This is a way of handling incoming Peppol messages
 *
 * @author Philip Helger
 */
@IsSPIImplementation
public class CustomPeppolIncomingSBDHandlerSPI implements IPhase4PeppolIncomingSBDHandlerSPI
{
  private static final Logger LOGGER = LoggerFactory.getLogger (CustomPeppolIncomingSBDHandlerSPI.class);

  public void handleIncomingSBD (@Nonnull final IAS4IncomingMessageMetadata aMessageMetadata,
                                 @Nonnull final HttpHeaderMap aHeaders,
                                 @Nonnull final Ebms3UserMessage aUserMessage,
                                 @Nonnull final byte [] aSBDBytes,
                                 @Nonnull final StandardBusinessDocument aSBD,
                                 @Nonnull final PeppolSBDHData aPeppolSBD,
                                 @Nonnull final IAS4IncomingMessageState aIncomingState,
                                 @Nonnull final ICommonsList <Ebms3Error> aProcessingErrorMessages) throws Exception
  {
    final String sMyPeppolSeatID = APConfig.getMyPeppolSeatID ();

    String senderId = aPeppolSBD.getSenderAsIdentifier().getURIEncoded();
    String receiverId = aPeppolSBD.getReceiverAsIdentifier().getURIEncoded();
    String docTypeId = aPeppolSBD.getDocumentTypeAsIdentifier().getURIEncoded();
    String processId = aPeppolSBD.getProcessAsIdentifier().getURIEncoded();
    String countryC1 = aPeppolSBD.getCountryC1();
    String body = Objects.requireNonNull(aPeppolSBD.getBusinessMessageAsTextContent()).toString();

    // Create JSON payload
    JsonObject payload = new JsonObject();
    payload.addProperty("senderId", senderId);
    payload.addProperty("receiverId", receiverId);
    payload.addProperty("docTypeId", docTypeId);
    payload.addProperty("processId", processId);
    payload.addProperty("countryC1", countryC1);
    payload.addProperty("body", body);

    // Send to endpoint
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(APConfig.getRecommandApiEndpoint() + "/api/internal/receiveDocument"))
          .header("Content-Type", "application/json")
          .header("X-Internal-Token", APConfig.getRecommandApiInternalToken())
          .POST(BodyPublishers.ofString(new Gson().toJson(payload)))
          .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      
      if (response.statusCode() != 200) {
        LOGGER.error("Failed to send document to endpoint. Status code: " + response.statusCode());
        throw new Exception("Failed to process document");
      }
      
      LOGGER.info("Successfully sent document to endpoint");
    } catch (Exception e) {
      // In case there is an error, throw any Exception -> will lead to an AS4
      // Error Message to the sender
      LOGGER.error("Error sending document to endpoint", e);
      throw e;
    }

    // Last action in this method
    new Thread ( () -> {
      // TODO If you have a way to determine the real end user of the message
      // here, this might be a good opportunity to store the data for Peppol
      // Reporting (do this asynchronously as the last activity)
      // Note: this is a separate thread so that it does not block the sending
      // of the positive receipt message

      // TODO Peppol Reporting - enable if possible to be done in here
      if (false)
        try
        {
          LOGGER.info ("Creating Peppol Reporting Item and storing it");

          // TODO determine correct values for the next three fields
          final String sC3ID = sMyPeppolSeatID;
          final String sC4CountryCode = "AT";
          final String sEndUserID = "EndUserID";

          // Create the reporting item
          final PeppolReportingItem aReportingItem = Phase4PeppolServletMessageProcessorSPI.createPeppolReportingItemForReceivedMessage (aUserMessage,
                                                                                                                                         aPeppolSBD,
                                                                                                                                         aIncomingState,
                                                                                                                                         sC3ID,
                                                                                                                                         sC4CountryCode,
                                                                                                                                         sEndUserID);
          PeppolReportingBackend.withBackendDo (AS4Configuration.getConfig (),
                                                aBackend -> aBackend.storeReportingItem (aReportingItem));
        }
        catch (final PeppolReportingBackendException ex)
        {
          LOGGER.error ("Failed to store Peppol Reporting Item", ex);
          // TODO improve error handling
        }
    }).start ();
  }
}

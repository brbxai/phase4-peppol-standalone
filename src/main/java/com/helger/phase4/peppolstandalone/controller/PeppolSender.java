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
package com.helger.phase4.peppolstandalone.controller;

import java.time.OffsetDateTime;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import com.helger.commons.annotation.Nonempty;
import com.helger.commons.datetime.PDTFactory;
import com.helger.commons.datetime.PDTWebDateHelper;
import com.helger.commons.lang.StackTraceHelper;
import com.helger.commons.system.EJavaVersion;
import com.helger.commons.timing.StopWatch;
import com.helger.commons.wrapper.Wrapper;
import com.helger.json.IJsonArray;
import com.helger.json.IJsonObject;
import com.helger.json.JsonArray;
import com.helger.json.JsonObject;
import com.helger.json.serialize.JsonWriterSettings;
import com.helger.peppol.sbdh.PeppolSBDHData;
import com.helger.peppol.sml.ISMLInfo;
import com.helger.peppol.utils.PeppolCAChecker;
import com.helger.peppol.utils.PeppolCertificateHelper;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.phase4.client.IAS4ClientBuildMessageCallback;
import com.helger.phase4.dump.AS4RawResponseConsumerWriteToFile;
import com.helger.phase4.ebms3header.Ebms3Error;
import com.helger.phase4.marshaller.Ebms3SignalMessageMarshaller;
import com.helger.phase4.model.message.AS4UserMessage;
import com.helger.phase4.model.message.AbstractAS4Message;
import com.helger.phase4.peppol.Phase4PeppolSender;
import com.helger.phase4.peppol.Phase4PeppolSender.PeppolUserMessageBuilder;
import com.helger.phase4.peppol.Phase4PeppolSender.PeppolUserMessageSBDHBuilder;
import com.helger.phase4.peppolstandalone.APConfig;
import com.helger.phase4.profile.peppol.Phase4PeppolHttpClientSettings;
import com.helger.phase4.sender.EAS4UserMessageSendResult;
import com.helger.phase4.util.Phase4Exception;
import com.helger.security.certificate.CertificateHelper;
import com.helger.smpclient.peppol.SMPClientReadOnly;
import com.helger.xml.serialize.read.DOMReader;

/**
 * This contains the main Peppol sending code. It was extracted from the
 * controller to make it more readable
 *
 * @author Philip Helger
 */
@Immutable
final class PeppolSender
{
  private static final Logger LOGGER = LoggerFactory.getLogger (PeppolSender.class);

  private PeppolSender ()
  {}

  /**
   * Send a Peppol message where the SBDH is created internally by phase4
   *
   * @param aSmlInfo
   *        The SML to be used for receiver lookup
   * @param aAPCAChecker
   *        The Peppol CA checker to be used.
   * @param aPayloadBytes
   *        The main business document to be send
   * @param sSenderID
   *        The Peppol sender Participant ID
   * @param sReceiverID
   *        The Peppol receiver Participant ID
   * @param sDocTypeID
   *        The Peppol document type ID
   * @param sProcessID
   *        The Peppol process ID
   * @param sCountryCodeC1
   *        The Country Code of the sender (C1)
   * @return The created JSON with the sending results
   */
  @Nonnull
  static String sendPeppolMessageCreatingSbdh (@Nonnull final ISMLInfo aSmlInfo,
                                               @Nonnull final PeppolCAChecker aAPCAChecker,
                                               @Nonnull final byte [] aPayloadBytes,
                                               @Nonnull @Nonempty final String sSenderID,
                                               @Nonnull @Nonempty final String sReceiverID,
                                               @Nonnull @Nonempty final String sDocTypeID,
                                               @Nonnull @Nonempty final String sProcessID,
                                               @Nonnull @Nonempty final String sCountryCodeC1)
  {
    final String sMyPeppolSeatID = APConfig.getMyPeppolSeatID ();

    final OffsetDateTime aNowUTC = PDTFactory.getCurrentOffsetDateTimeUTC ();
    final IJsonObject aJson = new JsonObject ();
    aJson.add ("currentDateTimeUTC", PDTWebDateHelper.getAsStringXSD (aNowUTC));
    aJson.add ("senderId", sSenderID);
    aJson.add ("receiverId", sReceiverID);
    aJson.add ("docTypeId", sDocTypeID);
    aJson.add ("processId", sProcessID);
    aJson.add ("countryC1", sCountryCodeC1);
    aJson.add ("senderPartyId", sMyPeppolSeatID);

    EAS4UserMessageSendResult eResult = null;
    boolean bExceptionCaught = false;
    final StopWatch aSW = StopWatch.createdStarted ();
    try
    {
      // Payload must be XML - even for Text and Binary content
      final Document aDoc = DOMReader.readXMLDOM (aPayloadBytes);
      if (aDoc == null)
        throw new IllegalStateException ("Failed to read provided payload as XML");

      // Start configuring here
      final IParticipantIdentifier aReceiverID = Phase4PeppolSender.IF.createParticipantIdentifierWithDefaultScheme (sReceiverID);

      final SMPClientReadOnly aSMPClient = new SMPClientReadOnly (Phase4PeppolSender.URL_PROVIDER,
                                                                  aReceiverID,
                                                                  aSmlInfo);

      aSMPClient.withHttpClientSettings (aHCS -> {
        // TODO Add SMP outbound proxy settings here
        // If this block is not used, it may be removed
      });

      if (EJavaVersion.getCurrentVersion ().isNewerOrEqualsThan (EJavaVersion.JDK_17))
      {
        // Work around the disabled SHA-1 in XMLDsig issue
        aSMPClient.setSecureValidation (false);
      }

      final Phase4PeppolHttpClientSettings aHCS = new Phase4PeppolHttpClientSettings ();
      // TODO Add AP outbound proxy settings here

      final PeppolUserMessageBuilder aBuilder;
      aBuilder = Phase4PeppolSender.builder ()
                                   .httpClientFactory (aHCS)
                                   .documentTypeID (Phase4PeppolSender.IF.createDocumentTypeIdentifierWithDefaultScheme (sDocTypeID))
                                   .processID (Phase4PeppolSender.IF.createProcessIdentifierWithDefaultScheme (sProcessID))
                                   .senderParticipantID (Phase4PeppolSender.IF.createParticipantIdentifierWithDefaultScheme (sSenderID))
                                   .receiverParticipantID (aReceiverID)
                                   .senderPartyID (sMyPeppolSeatID)
                                   .countryC1 (sCountryCodeC1)
                                   .payload (aDoc.getDocumentElement ())
                                   .peppolAP_CAChecker (aAPCAChecker)
                                   .smpClient (aSMPClient)
                                   .rawResponseConsumer (new AS4RawResponseConsumerWriteToFile ())
                                   .endpointURLConsumer (sEndpointUrl -> {
                                     // Determined by SMP lookup
                                     aJson.add ("c3EndpointUrl", sEndpointUrl);
                                   })
                                   .certificateConsumer ( (aAPCertificate, aCheckDT, eCertCheckResult) -> {
                                     // Determined by SMP lookup
                                     aJson.add ("c3Cert", CertificateHelper.getPEMEncodedCertificate (aAPCertificate));
                                     aJson.add ("c3CertSubjectCN",
                                                PeppolCertificateHelper.getSubjectCN (aAPCertificate));
                                     aJson.add ("c3CertCheckDT", PDTWebDateHelper.getAsStringXSD (aCheckDT));
                                     aJson.add ("c3CertCheckResult", eCertCheckResult);
                                   })
                                   .buildMessageCallback (new IAS4ClientBuildMessageCallback ()
                                   {
                                     public void onAS4Message (@Nonnull final AbstractAS4Message <?> aMsg)
                                     {
                                       // Created AS4 fields
                                       final AS4UserMessage aUserMsg = (AS4UserMessage) aMsg;
                                       aJson.add ("as4MessageId",
                                                  aUserMsg.getEbms3UserMessage ().getMessageInfo ().getMessageId ());
                                       aJson.add ("as4ConversationId",
                                                  aUserMsg.getEbms3UserMessage ()
                                                          .getCollaborationInfo ()
                                                          .getConversationId ());
                                     }
                                   })
                                   .signalMsgConsumer ( (aSignalMsg, aMessageMetadata, aState) -> {
                                     aJson.add ("as4ReceivedSignalMsg",
                                                new Ebms3SignalMessageMarshaller ().getAsString (aSignalMsg));

                                     if (aSignalMsg.hasErrorEntries ())
                                     {
                                       final IJsonArray aErrors = new JsonArray ();
                                       for (final Ebms3Error aError : aSignalMsg.getError ())
                                       {
                                         final IJsonObject aErrorDetails = new JsonObject ();
                                         if (aError.getDescription () != null)
                                           aErrorDetails.add ("description", aError.getDescriptionValue ());
                                         if (aError.getErrorDetail () != null)
                                           aErrorDetails.add ("errorDetails", aError.getErrorDetail ());
                                         if (aError.getCategory () != null)
                                           aErrorDetails.add ("category", aError.getCategory ());
                                         if (aError.getRefToMessageInError () != null)
                                           aErrorDetails.add ("refToMessageInError", aError.getRefToMessageInError ());
                                         if (aError.getErrorCode () != null)
                                           aErrorDetails.add ("errorCode", aError.getErrorCode ());
                                         if (aError.getOrigin () != null)
                                           aErrorDetails.add ("origin", aError.getOrigin ());
                                         if (aError.getSeverity () != null)
                                           aErrorDetails.add ("severity", aError.getSeverity ());
                                         if (aError.getShortDescription () != null)
                                           aErrorDetails.add ("shortDescription", aError.getShortDescription ());
                                         aErrors.add (aErrorDetails);
                                         LOGGER.warn ("AS4 error received: " + aErrorDetails.getAsJsonString ());
                                       }
                                       aJson.add ("as4ResponseErrors", aErrors);
                                       aJson.add ("as4ResponseError", true);
                                     }
                                     else
                                       aJson.add ("as4ResponseError", false);
                                   })
                                   .disableValidation ();
      final Wrapper <Phase4Exception> aCaughtEx = new Wrapper <> ();
      eResult = aBuilder.sendMessageAndCheckForReceipt (aCaughtEx::set);
      LOGGER.info ("Peppol client send result: " + eResult);

      if (eResult.isSuccess ())
      {
        // TODO determine the enduser ID of the outbound message
        // In many simple cases, this might be the sender's participant ID
        final String sEndUserID = "TODO";

        // TODO Enable when ready
        if (false)
          aBuilder.createAndStorePeppolReportingItemAfterSending (sEndUserID);
      }

      aJson.add ("sendingResult", eResult);

      if (aCaughtEx.isSet ())
      {
        final Phase4Exception ex = aCaughtEx.get ();
        LOGGER.error ("Error sending Peppol message via AS4", ex);
        aJson.add ("sendingException",
                   new JsonObject ().add ("class", ex.getClass ().getName ())
                                    .add ("message", ex.getMessage ())
                                    .add ("stackTrace", StackTraceHelper.getStackAsString (ex)));
        bExceptionCaught = true;
      }
    }
    catch (final Exception ex)
    {
      // Mostly errors on HTTP level
      LOGGER.error ("Error sending Peppol message via AS4", ex);
      aJson.add ("sendingException",
                 new JsonObject ().add ("class", ex.getClass ().getName ())
                                  .add ("message", ex.getMessage ())
                                  .add ("stackTrace", StackTraceHelper.getStackAsString (ex)));
      bExceptionCaught = true;
    }
    finally
    {
      aSW.stop ();
      aJson.add ("overallDurationMillis", aSW.getMillis ());
    }

    // Result may be null
    final boolean bSendingSuccess = eResult != null && eResult.isSuccess ();
    aJson.add ("sendingSuccess", bSendingSuccess);
    aJson.add ("overallSuccess", bSendingSuccess && !bExceptionCaught);

    // Return result JSON
    return aJson.getAsJsonString (JsonWriterSettings.DEFAULT_SETTINGS_FORMATTED);
  }

  /**
   * Send a Peppol message where the SBDH is passed in from the outside
   *
   * @param aData
   *        The Peppol SBDH data to be send
   * @param aSmlInfo
   *        The SML to be used for receiver lookup
   * @param aAPCAChecker
   *        The Peppol CA checker to be used.
   * @return The created JSON with the sending results
   */
  @Nonnull
  static String sendPeppolMessagePredefinedSbdh (@Nonnull final PeppolSBDHData aData,
                                                 @Nonnull final ISMLInfo aSmlInfo,
                                                 @Nonnull final PeppolCAChecker apCAChecker)
  {
    final String sMyPeppolSeatID = APConfig.getMyPeppolSeatID ();

    final OffsetDateTime aNowUTC = PDTFactory.getCurrentOffsetDateTimeUTC ();
    final IJsonObject aJson = new JsonObject ();
    aJson.add ("currentDateTimeUTC", PDTWebDateHelper.getAsStringXSD (aNowUTC));
    aJson.add ("senderId", aData.getSenderAsIdentifier ().getURIEncoded ());
    aJson.add ("receiverId", aData.getReceiverAsIdentifier ().getURIEncoded ());
    aJson.add ("docTypeId", aData.getDocumentTypeAsIdentifier ().getURIEncoded ());
    aJson.add ("processId", aData.getProcessAsIdentifier ().getURIEncoded ());
    aJson.add ("countryC1", aData.getCountryC1 ());
    aJson.add ("senderPartyId", sMyPeppolSeatID);

    EAS4UserMessageSendResult eResult = null;
    boolean bExceptionCaught = false;
    final StopWatch aSW = StopWatch.createdStarted ();
    try
    {
      // Start configuring here
      final IParticipantIdentifier aReceiverID = aData.getReceiverAsIdentifier ();

      final SMPClientReadOnly aSMPClient = new SMPClientReadOnly (Phase4PeppolSender.URL_PROVIDER,
                                                                  aReceiverID,
                                                                  aSmlInfo);

      aSMPClient.withHttpClientSettings (aHCS -> {
        // TODO Add SMP outbound proxy settings here
        // If this block is not used, it may be removed
      });

      if (EJavaVersion.getCurrentVersion ().isNewerOrEqualsThan (EJavaVersion.JDK_17))
      {
        // Work around the disabled SHA-1 in XMLDsig issue
        aSMPClient.setSecureValidation (false);
      }

      final Phase4PeppolHttpClientSettings aHCS = new Phase4PeppolHttpClientSettings ();
      // TODO Add AP outbound proxy settings here

      final PeppolUserMessageSBDHBuilder aBuilder;
      aBuilder = Phase4PeppolSender.sbdhBuilder ()
                                   .httpClientFactory (aHCS)
                                   .payloadAndMetadata (aData)
                                   .senderPartyID (sMyPeppolSeatID)
                                   .peppolAP_CAChecker (apCAChecker)
                                   .smpClient (aSMPClient)
                                   .rawResponseConsumer (new AS4RawResponseConsumerWriteToFile ())
                                   .endpointURLConsumer (sEndpointUrl -> {
                                     // Determined by SMP lookup
                                     aJson.add ("c3EndpointUrl", sEndpointUrl);
                                   })
                                   .certificateConsumer ( (aAPCertificate, aCheckDT, eCertCheckResult) -> {
                                     // Determined by SMP lookup
                                     aJson.add ("c3Cert", CertificateHelper.getPEMEncodedCertificate (aAPCertificate));
                                     aJson.add ("c3CertSubjectCN",
                                                PeppolCertificateHelper.getSubjectCN (aAPCertificate));
                                     aJson.add ("c3CertCheckDT", PDTWebDateHelper.getAsStringXSD (aCheckDT));
                                     aJson.add ("c3CertCheckResult", eCertCheckResult);
                                   })
                                   .buildMessageCallback (new IAS4ClientBuildMessageCallback ()
                                   {
                                     public void onAS4Message (@Nonnull final AbstractAS4Message <?> aMsg)
                                     {
                                       // Created AS4 fields
                                       final AS4UserMessage aUserMsg = (AS4UserMessage) aMsg;
                                       aJson.add ("as4MessageId",
                                                  aUserMsg.getEbms3UserMessage ().getMessageInfo ().getMessageId ());
                                       aJson.add ("as4ConversationId",
                                                  aUserMsg.getEbms3UserMessage ()
                                                          .getCollaborationInfo ()
                                                          .getConversationId ());
                                     }
                                   })
                                   .signalMsgConsumer ( (aSignalMsg, aMessageMetadata, aState) -> {
                                     aJson.add ("as4ReceivedSignalMsg",
                                                new Ebms3SignalMessageMarshaller ().getAsString (aSignalMsg));

                                     if (aSignalMsg.hasErrorEntries ())
                                     {
                                       aJson.add ("as4ResponseError", true);
                                       final IJsonArray aErrors = new JsonArray ();
                                       for (final Ebms3Error aError : aSignalMsg.getError ())
                                       {
                                         final IJsonObject aErrorDetails = new JsonObject ();
                                         if (aError.getDescription () != null)
                                           aErrorDetails.add ("description", aError.getDescriptionValue ());
                                         if (aError.getErrorDetail () != null)
                                           aErrorDetails.add ("errorDetails", aError.getErrorDetail ());
                                         if (aError.getCategory () != null)
                                           aErrorDetails.add ("category", aError.getCategory ());
                                         if (aError.getRefToMessageInError () != null)
                                           aErrorDetails.add ("refToMessageInError", aError.getRefToMessageInError ());
                                         if (aError.getErrorCode () != null)
                                           aErrorDetails.add ("errorCode", aError.getErrorCode ());
                                         if (aError.getOrigin () != null)
                                           aErrorDetails.add ("origin", aError.getOrigin ());
                                         if (aError.getSeverity () != null)
                                           aErrorDetails.add ("severity", aError.getSeverity ());
                                         if (aError.getShortDescription () != null)
                                           aErrorDetails.add ("shortDescription", aError.getShortDescription ());
                                         aErrors.add (aErrorDetails);
                                         LOGGER.warn ("AS4 error received: " + aErrorDetails.getAsJsonString ());
                                       }
                                       aJson.add ("as4ResponseErrors", aErrors);
                                     }
                                     else
                                       aJson.add ("as4ResponseError", false);
                                   });
      final Wrapper <Phase4Exception> aCaughtEx = new Wrapper <> ();
      eResult = aBuilder.sendMessageAndCheckForReceipt (aCaughtEx::set);
      LOGGER.info ("Peppol client send result: " + eResult);

      if (eResult.isSuccess ())
      {
        // TODO determine the enduser ID of the outbound message
        // In many simple cases, this might be the sender's participant ID
        final String sEndUserID = "TODO";

        // TODO Enable when ready
        if (false)
          aBuilder.createAndStorePeppolReportingItemAfterSending (sEndUserID);
      }

      aJson.add ("sendingResult", eResult);

      if (aCaughtEx.isSet ())
      {
        final Phase4Exception ex = aCaughtEx.get ();
        LOGGER.error ("Error sending Peppol message via AS4", ex);
        aJson.add ("sendingException",
                   new JsonObject ().add ("class", ex.getClass ().getName ())
                                    .add ("message", ex.getMessage ())
                                    .add ("stackTrace", StackTraceHelper.getStackAsString (ex)));
        bExceptionCaught = true;
      }
    }
    catch (final Exception ex)
    {
      // Mostly errors on HTTP level
      LOGGER.error ("Error sending Peppol message via AS4", ex);
      aJson.add ("sendingException",
                 new JsonObject ().add ("class", ex.getClass ().getName ())
                                  .add ("message", ex.getMessage ())
                                  .add ("stackTrace", StackTraceHelper.getStackAsString (ex)));
      bExceptionCaught = true;
    }
    finally
    {
      aSW.stop ();
      aJson.add ("overallDurationMillis", aSW.getMillis ());
    }

    // Result may be null
    final boolean bSendingSuccess = eResult != null && eResult.isSuccess ();
    aJson.add ("sendingSuccess", bSendingSuccess);
    aJson.add ("overallSuccess", bSendingSuccess && !bExceptionCaught);

    // Return result JSON
    return aJson.getAsJsonString (JsonWriterSettings.DEFAULT_SETTINGS_FORMATTED);
  }
}

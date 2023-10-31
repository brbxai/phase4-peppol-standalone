/*
 * Copyright (C) 2021-2023 Philip Helger (www.helger.com)
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
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unece.cefact.namespaces.sbdh.StandardBusinessDocument;

import com.helger.commons.annotation.IsSPIImplementation;
import com.helger.commons.http.HttpHeaderMap;
import com.helger.peppol.sbdh.PeppolSBDHDocument;
import com.helger.phase4.ebms3header.Ebms3UserMessage;
import com.helger.phase4.messaging.IAS4IncomingMessageMetadata;
import com.helger.phase4.peppol.servlet.IPhase4PeppolIncomingSBDHandlerSPI;
import com.helger.phase4.servlet.IAS4MessageState;

/**
 * This is one way of handling incoming messages: have an interface
 * {@link IPeppolIncomingHandler} that mimics the parameters of the
 * {@link IPhase4PeppolIncomingSBDHandlerSPI} handling method. Use a static
 * member of this class to set it. Each invocation of the SPI triggers a call to
 * the registered handler.<br>
 * Based on https://github.com/phax/phase4/discussions/115
 *
 * @author Philip Helger
 */
@IsSPIImplementation
public class CustomPeppolIncomingSBDHandlerSPI implements IPhase4PeppolIncomingSBDHandlerSPI
{
  private static final Logger LOGGER = LoggerFactory.getLogger (CustomPeppolIncomingSBDHandlerSPI.class);

  private static IPeppolIncomingHandler s_aHandler = null;

  /**
   * @return The statically defined incoming handler. May be <code>null</code>.
   *         Is <code>null</code> by default.
   */
  @Nullable
  public static IPeppolIncomingHandler getIncomingHandler ()
  {
    return s_aHandler;
  }

  /**
   * Call this method once on application startup. Should only be called once.
   *
   * @param aHandler
   *        The handler to be used. May be <code>null</code> but makes no sense.
   */
  public static void setIncomingHandler (@Nullable final IPeppolIncomingHandler aHandler)
  {
    if (s_aHandler != null && aHandler != null)
      LOGGER.warn ("Overwriting static handler for incoming Peppol messages");
    s_aHandler = aHandler;
  }

  public void handleIncomingSBD (@Nonnull final IAS4IncomingMessageMetadata aMessageMetadata,
                                 @Nonnull final HttpHeaderMap aHeaders,
                                 @Nonnull final Ebms3UserMessage aUserMessage,
                                 @Nonnull final byte [] aSBDBytes,
                                 @Nonnull final StandardBusinessDocument aSBD,
                                 @Nonnull final PeppolSBDHDocument aPeppolSBD,
                                 @Nonnull final IAS4MessageState aState) throws Exception
  {
    if (s_aHandler != null)
    {
      LOGGER.info ("Invoking the registered IPeppolIncomingHandler");
      try
      {
        s_aHandler.handleIncomingSBD (aMessageMetadata, aHeaders, aUserMessage, aSBDBytes, aSBD, aPeppolSBD, aState);
      }
      finally
      {
        // Also called in case of Exceptions
        LOGGER.info ("Finished invoking the registered IPeppolIncomingHandler");
      }
    }
    else
      LOGGER.error ("No IPeppolIncomingHandler is registered. Make sure to call 'setIncomingHandler' of this class on application startup");
  }

  @Override
  public boolean exceptionTranslatesToAS4Error ()
  {
    // If we have an Exception, tell the sender so
    return true;
  }
}

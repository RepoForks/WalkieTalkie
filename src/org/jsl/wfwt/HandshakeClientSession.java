/*
 * Copyright (C) 2015 Sergey Zubarev, info@js-labs.org
 *
 * This file is a part of WiFi WalkieTalkie application.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jsl.wfwt;

import android.util.Log;
import org.jsl.collider.RetainableByteBuffer;
import org.jsl.collider.Session;
import org.jsl.collider.StreamDefragger;
import org.jsl.collider.TimerQueue;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.concurrent.TimeUnit;

public class HandshakeClientSession implements Session.Listener
{
    private static final String LOG_TAG = "HandshakeClientSession";

    private final Channel m_channel;
    private final String m_serviceName;
    private final Session m_session;
    private final StreamDefragger m_streamDefragger;
    private final TimerQueue m_timerQueue;
    private final int m_pingInterval;
    private Runnable m_timerHandler;

    private class TimerHandler implements Runnable
    {
        public void run()
        {
            Log.i( LOG_TAG, getLogPrefix() + "session timeout, close connection." );
            m_session.closeConnection();
        }
    }

    private String getLogPrefix()
    {
        return m_channel.getName() + " (" + m_serviceName + ", " + m_session.getRemoteAddress() + "): ";
    }

    public HandshakeClientSession(
            Channel channel,
            String audioFormat,
            String serviceName,
            Session session,
            TimerQueue timerQueue,
            int pingInterval )
    {
        m_channel = channel;
        m_serviceName = serviceName;
        m_session = session;
        m_streamDefragger = new StreamDefragger( Protocol.Message.HEADER_SIZE )
        {
            protected int validateHeader( ByteBuffer header )
            {
                return Protocol.Message.getLength(header);
            }
        };
        m_timerQueue = timerQueue;
        m_pingInterval = pingInterval;

        /* Timer should be started before handshake request send. */
        if (pingInterval > 0)
        {
            m_timerHandler = new TimerHandler();
            timerQueue.schedule( m_timerHandler, pingInterval, TimeUnit.SECONDS );
        }

        try
        {
            final ByteBuffer handshakeRequest = Protocol.HandshakeRequest.create( audioFormat );
            session.sendData( handshakeRequest );
        }
        catch (final CharacterCodingException ex)
        {
            Log.e( LOG_TAG, getLogPrefix() + ex.toString() );
            session.closeConnection();
        }
    }

    public void onDataReceived( RetainableByteBuffer data )
    {
        if (m_timerHandler != null)
        {
            try
            {
                final int rc = m_timerQueue.cancel( m_timerHandler );
                if (rc != 0)
                {
                    /* Timer already fired, Session.closeConnection is called,
                     * or will be called very soon, so no any sense to handle anything.
                     */
                    return;
                }
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, ex.toString() );
                Thread.currentThread().interrupt();
            }
        }

        final RetainableByteBuffer msg = m_streamDefragger.getNext( data );
        if (msg == null)
        {
            /* HandshakeReply is fragmented, strange but can happen */
            Log.i( LOG_TAG, getLogPrefix() + "fragmented HandshakeReply." );
        }
        else
        {
            final int messageID = Protocol.Message.getID( msg );
            if (messageID == Protocol.HandshakeReply.ID)
            {
                final int status = Protocol.HandshakeReply.getStatus( msg );
                if (status == Protocol.StatusOk)
                {
                    try
                    {
                        final String audioFormat = Protocol.HandshakeReply.getString( msg );
                        final AudioPlayer audioPlayer = AudioPlayer.create( audioFormat );
                        if (audioPlayer == null)
                        {
                            Log.w( LOG_TAG,
                                    getLogPrefix() + ": unsupported audio format '" + audioFormat +
                                    "', closing connection." );
                            m_session.closeConnection();
                        }
                        else
                        {
                            final ChannelSession channelSession = new ChannelSession(
                                    m_channel, m_serviceName, m_session, audioPlayer, m_timerQueue, m_pingInterval );
                            m_session.replaceListener(channelSession);
                        }
                    }
                    catch (final CharacterCodingException ex)
                    {
                        Log.w( LOG_TAG, getLogPrefix() + ": " + ex.toString() + ", close connection." );
                        m_session.closeConnection();
                    }
                }
                else if (status == Protocol.StatusFail)
                {
                    String statusText = null;
                    try
                    {
                        statusText = Protocol.HandshakeReply.getString( msg );
                    }
                    catch (final CharacterCodingException ex)
                    {
                        Log.w( LOG_TAG, getLogPrefix() + ex.toString() );
                    }
                    Log.i( LOG_TAG, getLogPrefix() + "HandshakeRequest rejected: " + statusText + ", close connection." );
                    m_session.closeConnection();
                }
                else
                {
                    Log.i( LOG_TAG, getLogPrefix() + "invalid message received, close connection." );
                    m_session.closeConnection();
                }
            }
            else
            {
                Log.i( LOG_TAG, getLogPrefix() +
                        "unexpected message " + messageID + " received from " +
                        m_session.getRemoteAddress() + ", close connection." );
                m_session.closeConnection();
            }
        }
    }

    public void onConnectionClosed()
    {
        Log.i( LOG_TAG, getLogPrefix() + ": connection closed." );
        m_channel.removeSession( m_serviceName, m_session );
    }
}

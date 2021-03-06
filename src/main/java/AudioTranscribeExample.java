/*
 * Copyright 2015-2019 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.CombinedAudio;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;

import javax.annotation.Nonnull;
import javax.security.auth.login.LoginException;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Hashtable;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AudioTranscribeExample extends ListenerAdapter
{
    public static void main(String[] args) throws LoginException, IOException {
        final Properties properties = DiscordPropertiesReader.readProperties();
        final String token = properties.getProperty("discord.bot_token");
        JDABuilder.createDefault(token)                            // Use provided token from command line arguments
                .addEventListeners(new AudioTranscribeExample())  // Start listening with this listener
                .setActivity(Activity.listening("to jams")) // Inform users that we are jammin' it out
                .setStatus(OnlineStatus.DO_NOT_DISTURB)     // Please don't disturb us while we're jammin'
                .build();                                   // Login with these options
        // Note that its not needed to explicitly enable audio here
    }

    @Override
    public void onGuildMessageReceived(GuildMessageReceivedEvent event)
    {
        Message message = event.getMessage();
        User author = message.getAuthor();
        String content = message.getContentRaw();
        Guild guild = event.getGuild();

        // Ignore message if bot
        if (author.isBot())
            return;

        if (content.startsWith("!echo "))
        {
            String arg = content.substring("!echo ".length());
            onEchoCommand(event, guild, arg);
        }
        else if(content.startsWith("!delay "))
        {
            String part =  content.substring("!delay ".length());
            int result = Integer.parseInt(part);
            AudioHandler.cachingTimeMilli = result;
        }
        else if (content.equals("!echo"))
        {
            onEchoCommand(event);
        }
    }

    /**
     * Handle command without arguments.
     *
     * @param event
     *        The event for this command
     */
    private void onEchoCommand(GuildMessageReceivedEvent event)
    {
        Member member = event.getMember();                              // Member is the context of the user for the specific guild, containing voice state and roles
        GuildVoiceState voiceState = member.getVoiceState();            // Check the current voice state of the user
        VoiceChannel channel = voiceState.getChannel();                 // Use the channel the user is currently connected to
        if (channel != null)
        {
            connectTo(channel);                                         // Join the channel of the user
            onConnecting(channel, event.getChannel());                  // Tell the user about our success
        }
        else
        {
            onUnknownChannel(event.getChannel(), "your voice channel"); // Tell the user about our failure
        }
    }

    /**
     * Handle command with arguments.
     *
     * @param event
     *        The event for this command
     * @param guild
     *        The guild where its happening
     * @param arg
     *        The input argument
     */
    private void onEchoCommand(GuildMessageReceivedEvent event, Guild guild, String arg)
    {
        boolean isNumber = arg.matches("\\d+"); // This is a regular expression that ensures the input consists of digits
        VoiceChannel channel = null;
        if (isNumber)                           // The input is an id?
        {
            channel = guild.getVoiceChannelById(arg);
        }
        if (channel == null)                    // Then the input must be a name?
        {
            List<VoiceChannel> channels = guild.getVoiceChannelsByName(arg, true);
            if (!channels.isEmpty())            // Make sure we found at least one exact match
                channel = channels.get(0);      // We found a channel! This cannot be null.
        }

        TextChannel textChannel = event.getChannel();
        if (channel == null)                    // I have no idea what you want mr user
        {
            onUnknownChannel(textChannel, arg); // Let the user know about our failure
            return;
        }
        connectTo(channel);                     // We found a channel to connect to!
        onConnecting(channel, textChannel);     // Let the user know, we were successful!
    }

    /**
     * Inform user about successful connection.
     *
     * @param channel
     *        The voice channel we connected to
     * @param textChannel
     *        The text channel to send the message in
     */
    private void onConnecting(VoiceChannel channel, TextChannel textChannel)
    {
        textChannel.sendMessage("Connecting to " + channel.getName()).queue(); // never forget to queue()!
    }

    /**
     * The channel to connect to is not known to us.
     *
     * @param channel
     *        The message channel (text channel abstraction) to send failure information to
     * @param comment
     *        The information of this channel
     */
    private void onUnknownChannel(MessageChannel channel, String comment)
    {
        channel.sendMessage("Unable to connect to ``" + comment + "``, no such channel!").queue(); // never forget to queue()!
    }

    /**
     * Connect to requested channel and start echo handler
     *
     * @param channel
     *        The channel to connect to
     */
    private void connectTo(VoiceChannel channel)
    {
        Guild guild = channel.getGuild();
        // Get an audio manager for this guild, this will be created upon first use for each guild
        AudioManager audioManager = guild.getAudioManager();
        // Create our Send/Receive handler for the audio connection
        AudioHandler handler = new AudioHandler();

        // The order of the following instructions does not matter!

        // Set the sending handler to our echo system
        audioManager.setSendingHandler(handler);
        // Set the receiving handler to the same echo system, otherwise we can't echo anything
        audioManager.setReceivingHandler(handler);
        // Connect to the voice channel
        audioManager.openAudioConnection(channel);
    }

    public static class AudioHandler implements AudioSendHandler, AudioReceiveHandler
    {
        /*
            All methods in this class are called by JDA threads when resources are available/ready for processing.

            The receiver will be provided with the latest 20ms of PCM stereo audio
            Note you can receive even while setting yourself to deafened!

            The sender will provide 20ms of PCM stereo audio (pass-through) once requested by JDA
            When audio is provided JDA will automatically set the bot to speaking!
         */
        public static int cachingTimeMilli = 1000;

        private ConcurrentHashMap<String, Queue<byte[]>> my_dict = new ConcurrentHashMap<String, Queue<byte[]>>();
        private ConcurrentHashMap<String, Long> my_flushTimers = new ConcurrentHashMap<String, Long>();
        private ConcurrentHashMap<String, Long> my_startTimers = new ConcurrentHashMap<String, Long>();

        Thread DumpPollThread;

        public AudioHandler()
        {
            DumpPollThread = new Thread(() -> {
               while(true)
               {
                   Long now = System.currentTimeMillis();
                   for (String usrnm: my_flushTimers.keySet())
                   {
                       Long LastPackRecieved = my_flushTimers.get(usrnm);
                       Long diff = now - LastPackRecieved;
                       if(diff > cachingTimeMilli)
                       {
                           Queue<byte[]> data = my_dict.remove(usrnm);
                           my_flushTimers.remove(usrnm);
                           Long startTime = my_startTimers.remove(usrnm);
                           if(data!=null) // just to be safe but it should never be this
                           {
                               String fName = usrnm+"-"+startTime;
                                try {
                                    FileOutputStream out = new FileOutputStream(fName);

                                    //byte[] buff = new byte[] {};
                                    int bytes = 0;
                                    for (byte[] payload : data)
                                    {
                                        bytes += payload.length;
                                    }
                                    byte[] buff = new byte[bytes];
                                    int currentIndex = 0;
                                    for(byte[] payload : data)
                                    {
                                        for(byte b : payload)
                                        {
                                            buff[currentIndex] = b;
                                            currentIndex++;
                                        }
                                    }
                                    out.write(buff);
                                    out.close();
                                } catch (FileNotFoundException e) {
                                    e.printStackTrace();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                           }
                       }
                   }
               }
            });
            DumpPollThread.start();
        }

        public void dumpUserVoiceStream()
        {

        }

        /* Receive Handling */

        @Override // combine multiple user audio-streams into a single one
        public boolean canReceiveCombined()
        {
            // limit queue to 10 entries, if that is exceeded we can not receive more until the send system catches up
            return false; //queue.size() < 10;
        }

        @Override
        public boolean canReceiveUser()
        {
            return true;
        }

        @Override
        public void handleUserAudio(@Nonnull UserAudio userAudio)
        {
            String usrnm = userAudio.getUser().getName();
            if(!my_dict.containsKey(usrnm))
            {
                my_dict.put(usrnm, new ConcurrentLinkedQueue<>());
            }
            byte[] data = userAudio.getAudioData(1.0f);
            Queue<byte[]> x = my_dict.get(usrnm);
            x.add(data);
            my_dict.put(usrnm, x);


            my_flushTimers.put(usrnm, System.currentTimeMillis());
            if(!my_startTimers.containsKey(usrnm))
            {
                Long currTimeInMilliSec = System.currentTimeMillis();
                my_startTimers.put(usrnm, currTimeInMilliSec);
            }
        }

        @Override
        public void handleCombinedAudio(CombinedAudio combinedAudio)
        {
            // we only want to send data when a user actually sent something, otherwise we would just send silence
            /*
            if (combinedAudio.getUsers().isEmpty())
                return;

            byte[] data = combinedAudio.getAudioData(1.0f); // volume at 100% = 1.0 (50% = 0.5 / 55% = 0.55)
            Thread newThread = new Thread(() -> {
                //try {
                //    Thread.sleep(cachingTimeMilli);
                //} catch (InterruptedException e) {
                //    e.printStackTrace();
                //}
                queue.add(data);
            });
            newThread.start();
            */
        }
/*
        Disable per-user audio since we want to echo the entire channel and not specific users.

        @Override // give audio separately for each user that is speaking
        public boolean canReceiveUser()
        {
            // this is not useful if we want to echo the audio of the voice channel, thus disabled for this purpose
            return false;
        }

        @Override
        public void handleUserAudio(UserAudio userAudio) {} // per-user is not helpful in an echo system
*/

        /* Send Handling */

        @Override
        public boolean canProvide()
        {
            // If we have something in our buffer we can provide it to the send system
            return false; //!queue.isEmpty();
        }

        @Override
        public ByteBuffer provide20MsAudio()
        {
            // use what we have in our buffer to send audio as PCM
            byte[] data = new byte[] {};//queue.poll();
            return data == null ? null : ByteBuffer.wrap(data); // Wrap this in a java.nio.ByteBuffer
        }


        @Override
        public boolean isOpus()
        {
            // since we send audio that is received from discord.properties we don't have opus but PCM
            return false;
        }
    }
}

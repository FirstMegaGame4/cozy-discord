/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.cozy.modules.welcome

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.kotlindiscord.kord.extensions.DISCORD_RED
import com.kotlindiscord.kord.extensions.DiscordRelayedException
import com.kotlindiscord.kord.extensions.koin.KordExKoinComponent
import com.kotlindiscord.kord.extensions.utils.deleteIgnoringNotFound
import com.kotlindiscord.kord.extensions.utils.hasNotStatus
import com.kotlindiscord.kord.extensions.utils.scheduling.Scheduler
import com.kotlindiscord.kord.extensions.utils.scheduling.Task
import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.behavior.edit
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.event.interaction.InteractionCreateEvent
import dev.kord.core.supplier.EntitySupplyStrategy
import dev.kord.rest.builder.message.allowedMentions
import dev.kord.rest.builder.message.create.UserMessageCreateBuilder
import dev.kord.rest.builder.message.embed
import dev.kord.rest.request.RestRequestException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.decodeFromString
import org.koin.core.component.inject
import org.quiltmc.community.cozy.modules.welcome.blocks.Block
import org.quiltmc.community.cozy.modules.welcome.blocks.InteractionBlock
import org.quiltmc.community.cozy.modules.welcome.config.WelcomeChannelConfig
import kotlin.collections.set

public class WelcomeChannel(
	public val channel: GuildMessageChannel,
	public val url: String,
) : KordExKoinComponent {
	private var blocks: MutableList<Block> = mutableListOf()

	private val messageMapping: MutableMap<Snowflake, Block> = mutableMapOf()

	private val config: WelcomeChannelConfig by inject()
	private val client = HttpClient()

	private lateinit var yaml: Yaml
	private var task: Task? = null

	public val scheduler: Scheduler = Scheduler()

	public suspend fun handleInteraction(event: InteractionCreateEvent) {
		blocks.forEach {
			if (it is InteractionBlock) {
				it.handleInteraction(event)
			}
		}
	}

	public suspend fun setup() {
		val taskDelay = config.getRefreshDelay()

		if (!::yaml.isInitialized) {
			yaml = Yaml(
				config.getSerializersModule(),
				YamlConfiguration(polymorphismStyle = PolymorphismStyle.Property)
			)
		}

		task?.cancel()

		if (taskDelay != null) {
			task = scheduler.schedule(taskDelay, false) {
				populate()
			}
		}

		populate()

		task?.start()
	}

	public fun shutdown() {
		task?.cancel()
		scheduler.shutdown()
	}

	private suspend fun fetchBlocks(): List<Block> {
		try {
			val response = client.get(url).body<String>()

			return yaml.decodeFromString(response)
		} catch (e: ClientRequestException) {
			throw DiscordRelayedException("Failed to download the YAML file\n\n>>> $e")
		} catch (e: YamlException) {
			throw DiscordRelayedException("Failed to parse the given YAML\n\n>>> $e")
		}
	}

	public fun getBlocks(): List<Block> =
		blocks.toList()

	public suspend fun populate() {
		task?.cancel()

		val guild = channel.getGuild()

		@Suppress("TooGenericExceptionCaught")
		try {
			blocks = fetchBlocks().toMutableList()
		} catch (e: Exception) {
			log {
				embed {
					title = "Welcome channel update failed"
					color = DISCORD_RED

					description = buildString {
						appendLine("**__Failed to update blocks__**")
						appendLine()
						appendLine("```")
						appendLine(e)
						appendLine("```")
					}

					field {
						name = "Channel"
						value = "${channel.mention} (`${channel.id}` / `${channel.name}`)"
					}
				}
			}

			throw e
		}

		blocks.forEach {
			it.channel = channel
			it.guild = guild
		}

		val messages = channel.withStrategy(EntitySupplyStrategy.rest)
			.messages
			.filter { it.author?.id == channel.kord.selfId }
			.filter { it.type == MessageType.Default }
			.toList()
			.sortedBy { it.id.timestamp }

		@Suppress("TooGenericExceptionCaught")
		try {
			if (messages.size > blocks.size) {
				messages.forEachIndexed { index, message ->
					val block = blocks.getOrNull(index)

					if (block != null) {
						if (messageNeedsUpdate(message, block)) {
							message.edit {
								block.edit(this)

								allowedMentions { }
							}
						}

						messageMapping[message.id] = block
					} else {
						message.delete()
						messageMapping.remove(message.id)
					}
				}
			} else {
				blocks.forEachIndexed { index, block ->
					val message = messages.getOrNull(index)

					if (message != null) {
						if (messageNeedsUpdate(message, block)) {
							message.edit {
								block.edit(this)

								allowedMentions { }
							}
						}

						messageMapping[message.id] = block
					} else {
						val newMessage = channel.createMessage {
							block.create(this)

							allowedMentions { }
						}

						messageMapping[newMessage.id] = block
					}
				}
			}
		} catch (e: Exception) {
			log {
				embed {
					title = "Welcome channel update failed"
					color = DISCORD_RED

					description = buildString {
						appendLine("**__Failed to update messages__**")
						appendLine()
						appendLine("```")
						appendLine(e)
						appendLine("```")
					}

					field {
						name = "Channel"
						value = "${channel.mention} (`${channel.id}` / `${channel.name}`)"
					}
				}
			}

			throw e
		}

		task?.start()
	}

	public suspend fun log(builder: suspend UserMessageCreateBuilder.() -> Unit): Message? =
		config.getLoggingChannel(channel, channel.guild.asGuild())?.createMessage { builder() }

	public suspend fun clear() {
		val messages = channel.withStrategy(EntitySupplyStrategy.rest)
			.messages
			.toList()
			.filter { it.type == MessageType.Default }

		try {
			channel.bulkDelete(messages.map { it.id })
		} catch (e: RestRequestException) {
			if (e.hasNotStatus(HttpStatusCode.NotFound)) {
				@Suppress("TooGenericExceptionCaught")
				try {
					messages.forEach { it.deleteIgnoringNotFound() }
				} catch (e: Exception) {
					log {
						embed {
							title = "Failed to clear welcome channel"
							color = DISCORD_RED

							description = buildString {
								appendLine("**__Failed to clear channel__**")
								appendLine()
								appendLine("```")
								appendLine(e)
								appendLine("```")
							}

							field {
								name = "Channel"
								value = "${channel.mention} (`${channel.id}` / `${channel.name}`)"
							}
						}
					}

					throw e
				}
			}
		}
	}

	private suspend fun messageNeedsUpdate(message: Message, block: Block): Boolean {
		val builder = UserMessageCreateBuilder()

		block.create(builder)

		return !builder.isSimilar(message)
	}
}

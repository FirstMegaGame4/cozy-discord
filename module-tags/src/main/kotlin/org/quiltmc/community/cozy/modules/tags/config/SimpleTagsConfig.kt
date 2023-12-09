/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.quiltmc.community.cozy.modules.tags.config

import com.kotlindiscord.kord.extensions.checks.types.Check
import dev.kord.core.entity.Guild
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.rest.builder.message.embed
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.lastOrNull
import org.quiltmc.community.cozy.modules.tags.TagFormatter

/**
 * A simple in-memory configuration class, useful if you don't need anything special for your config storage.
 *
 * Comes with a convenient builder, for easy configuration.
 */
public class SimpleTagsConfig(private val builder: Builder) : TagsConfig {
	override suspend fun getTagFormatter(): TagFormatter =
		builder.tagFormatter

	override suspend fun getUserCommandChecks(): List<Check<*>> =
		builder.userCommandChecks

	override suspend fun getStaffCommandChecks(): List<Check<*>> =
		builder.staffCommandChecks

	override suspend fun getLoggingChannelOrNull(guild: Guild): GuildMessageChannel? =
		if (builder.loggingChannelName != null) {
			guild.channels
				.filterIsInstance<GuildMessageChannel>()
				.filter { channel -> channel.name.equals(builder.loggingChannelName, true) }
				.lastOrNull()
		} else {
			null
		}

	public class Builder {
		public var tagFormatter: TagFormatter = { tag ->
			embed {
				title = tag.title
				description = tag.description
				color = tag.color

				footer {
					text = "${tag.category}/${tag.key}"
				}

				image = tag.image
			}
		}

		public var loggingChannelName: String? = null

		internal val userCommandChecks: MutableList<Check<*>> = mutableListOf()
		internal val staffCommandChecks: MutableList<Check<*>> = mutableListOf()

		public fun userCommandCheck(body: Check<*>) {
			userCommandChecks.add(body)
		}

		public fun staffCommandCheck(body: Check<*>) {
			staffCommandChecks.add(body)
		}
	}
}

public fun SimpleTagsConfig(body: SimpleTagsConfig.Builder.() -> Unit): SimpleTagsConfig {
	val builder = SimpleTagsConfig.Builder()

	body(builder)

	return SimpleTagsConfig(builder)
}

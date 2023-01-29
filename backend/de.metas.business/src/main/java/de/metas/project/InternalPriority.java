/*
 * #%L
 * de.metas.business
 * %%
 * Copyright (C) 2023 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

package de.metas.project;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import de.metas.util.lang.ReferenceListAwareEnum;
import lombok.Getter;
import lombok.NonNull;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.X_C_Project;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Optional;

/**
 * Keep in sync with reference list "_PriorityRule" {@code AD_Reference_ID=154}
 */
public enum InternalPriority implements ReferenceListAwareEnum
{
	HIGH(X_C_Project.INTERNALPRIORITY_High),
	MEDIUM(X_C_Project.INTERNALPRIORITY_Medium),
	LOW(X_C_Project.INTERNALPRIORITY_Low),
	URGENT(X_C_Project.INTERNALPRIORITY_Urgent),
	MINOR(X_C_Project.INTERNALPRIORITY_Minor);

	@Getter
	private final String code;

	InternalPriority(@NonNull final String code)
	{
		this.code = code;
	}

	@Nullable
	public static InternalPriority ofNullableCode(@Nullable final String code)
	{
		return code != null ? ofCode(code) : null;
	}

	@Nullable
	public static String toCode(@Nullable final InternalPriority internalPriority)
	{
		return Optional.ofNullable(internalPriority)
				.map(InternalPriority::getCode)
				.orElse(null);
	}

	public static InternalPriority ofCode(@NonNull final String code)
	{
		final InternalPriority priority = prioritiesByCode.get(code);
		if (priority == null)
		{
			throw new AdempiereException("No " + InternalPriority.class + " found for code: " + code);
		}
		return priority;
	}

	private static final ImmutableMap<String, InternalPriority> prioritiesByCode = Maps.uniqueIndex(Arrays.asList(values()), InternalPriority::getCode);
}

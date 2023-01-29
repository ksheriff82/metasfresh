/*
 * #%L
 * de.metas.cucumber
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

package de.metas.cucumber.stepdefs.docType;

import de.metas.cucumber.stepdefs.DataTableUtil;
import de.metas.util.Check;
import de.metas.util.Services;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.And;
import lombok.NonNull;
import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.dao.IQueryBuilder;
import org.compiere.model.I_C_DocType;
import org.compiere.model.I_C_DocType_Invoicing_Pool;

import java.util.List;
import java.util.Map;

import static de.metas.cucumber.stepdefs.StepDefConstants.TABLECOLUMN_IDENTIFIER;
import static org.adempiere.model.InterfaceWrapperHelper.saveRecord;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.compiere.model.I_C_DocType.COLUMNNAME_C_DocType_ID;
import static org.compiere.model.I_C_DocType_Invoicing_Pool.COLUMNNAME_C_DocType_Invoicing_Pool_ID;

public class C_DocType_StepDef
{
	private final IQueryBL queryBL = Services.get(IQueryBL.class);

	private final C_DocType_Invoicing_Pool_StepDefData invoicingPoolTable;
	private final C_DocType_StepDefData docTypeTable;

	public C_DocType_StepDef(
			@NonNull final C_DocType_Invoicing_Pool_StepDefData invoicingPoolTable,
			@NonNull final C_DocType_StepDefData docTypeTable)
	{
		this.invoicingPoolTable = invoicingPoolTable;
		this.docTypeTable = docTypeTable;
	}

	@And("load C_DocType:")
	public void load_C_DocType(@NonNull final DataTable dataTable)
	{
		final List<Map<String, String>> tableRows = dataTable.asMaps(String.class, String.class);
		for (final Map<String, String> tableRow : tableRows)
		{
			loadDocType(tableRow);
		}
	}

	@And("update C_DocType:")
	public void update_C_DocType(@NonNull final DataTable dataTable)
	{
		final List<Map<String, String>> tableRows = dataTable.asMaps(String.class, String.class);
		for (final Map<String, String> tableRow : tableRows)
		{
			updateDocType(tableRow);
		}
	}

	private void loadDocType(@NonNull final Map<String, String> tableRow)
	{
		final IQueryBuilder<I_C_DocType> queryBuilder = queryBL.createQueryBuilder(I_C_DocType.class)
				.addOnlyActiveRecordsFilter();

		final String docBaseType = DataTableUtil.extractStringOrNullForColumnName(tableRow, "OPT." + I_C_DocType.COLUMNNAME_DocBaseType);
		if (Check.isNotBlank(docBaseType))
		{
			queryBuilder.addEqualsFilter(I_C_DocType.COLUMNNAME_DocBaseType, docBaseType);
		}

		final Boolean isDefault = DataTableUtil.extractBooleanForColumnNameOr(tableRow, "OPT." + I_C_DocType.COLUMNNAME_IsDefault, null);
		if (isDefault != null)
		{
			queryBuilder.addEqualsFilter(I_C_DocType.COLUMNNAME_IsDefault, isDefault);
		}

		final String docSubType = DataTableUtil.extractNullableStringForColumnName(tableRow, "OPT." + I_C_DocType.COLUMNNAME_DocSubType);
		if (Check.isNotBlank(docSubType))
		{
			queryBuilder.addEqualsFilter(I_C_DocType.COLUMNNAME_DocSubType, DataTableUtil.nullToken2Null(docSubType));
		}

		final String name = DataTableUtil.extractNullableStringForColumnName(tableRow, "OPT." + I_C_DocType.COLUMNNAME_Name);
		if (Check.isNotBlank(name))
		{
			queryBuilder.addEqualsFilter(I_C_DocType.COLUMNNAME_Name, DataTableUtil.nullToken2Null(name));
		}

		final I_C_DocType docType = queryBuilder.create().firstOnlyOrNull(I_C_DocType.class);

		assertThat(docType).isNotNull();

		final String docTypeIdentifier = DataTableUtil.extractStringForColumnName(tableRow, I_C_DocType.COLUMNNAME_C_DocType_ID + "." + TABLECOLUMN_IDENTIFIER);
		docTypeTable.putOrReplace(docTypeIdentifier, docType);
	}

	private void updateDocType(@NonNull final Map<String, String> tableRow)
	{
		final String docTypeIdentifier = DataTableUtil.extractStringForColumnName(tableRow, COLUMNNAME_C_DocType_ID + "." + TABLECOLUMN_IDENTIFIER);
		final I_C_DocType docTypeRecord = docTypeTable.get(docTypeIdentifier);
		assertThat(docTypeRecord).isNotNull();

		final String invoicingPoolIdentifier = DataTableUtil.extractNullableStringForColumnName(tableRow, "OPT." + COLUMNNAME_C_DocType_Invoicing_Pool_ID + "." + TABLECOLUMN_IDENTIFIER);
		if (Check.isNotBlank(invoicingPoolIdentifier))
		{
			final String invoicingPoolIdentifierValue = DataTableUtil.nullToken2Null(invoicingPoolIdentifier);
			if (invoicingPoolIdentifierValue != null)
			{
				final I_C_DocType_Invoicing_Pool docTypeInvoicingPoolRecord = invoicingPoolTable.get(invoicingPoolIdentifier);
				assertThat(docTypeInvoicingPoolRecord).isNotNull();

				docTypeRecord.setC_DocType_Invoicing_Pool_ID(docTypeInvoicingPoolRecord.getC_DocType_Invoicing_Pool_ID());
			}
			else
			{
				docTypeRecord.setC_DocType_Invoicing_Pool_ID(0);
			}
		}

		saveRecord(docTypeRecord);

		docTypeTable.putOrReplace(docTypeIdentifier, docTypeRecord);
	}
}

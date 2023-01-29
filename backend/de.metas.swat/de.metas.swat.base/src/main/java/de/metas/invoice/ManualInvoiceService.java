/*
 * #%L
 * de.metas.swat.base
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

package de.metas.invoice;

import com.google.common.collect.ImmutableList;
import de.metas.acct.accounts.ProductAcctType;
import de.metas.acct.api.AcctSchemaId;
import de.metas.acct.api.impl.ElementValueId;
import de.metas.bpartner.BPartnerLocationAndCaptureId;
import de.metas.bpartner.BPartnerLocationId;
import de.metas.bpartner.service.IBPartnerDAO;
import de.metas.currency.CurrencyPrecision;
import de.metas.currency.ICurrencyBL;
import de.metas.document.engine.IDocument;
import de.metas.document.engine.IDocumentBL;
import de.metas.invoice.acct.AccountTypeName;
import de.metas.invoice.acct.InvoiceAcct;
import de.metas.invoice.acct.InvoiceAcctRule;
import de.metas.invoice.acct.InvoiceAcctRuleMatcher;
import de.metas.invoice.acct.InvoiceAcctService;
import de.metas.invoice.request.CreateInvoiceRequest;
import de.metas.invoice.request.CreateInvoiceRequestHeader;
import de.metas.invoice.request.CreateInvoiceRequestLine;
import de.metas.invoice.request.CreateManualInvoiceLineRequest;
import de.metas.invoice.request.CreateManualInvoiceRequest;
import de.metas.location.CountryId;
import de.metas.location.LocationId;
import de.metas.money.Money;
import de.metas.organization.IOrgDAO;
import de.metas.po.CustomColumnService;
import de.metas.pricing.IEditablePricingContext;
import de.metas.pricing.IPricingResult;
import de.metas.pricing.PriceListId;
import de.metas.pricing.PricingSystemId;
import de.metas.pricing.service.IPriceListBL;
import de.metas.pricing.service.IPricingBL;
import de.metas.product.ProductPrice;
import de.metas.tax.api.ITaxDAO;
import de.metas.tax.api.Tax;
import de.metas.tax.api.TaxId;
import de.metas.tax.api.TaxQuery;
import de.metas.util.Services;
import lombok.NonNull;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.compiere.model.I_M_PriceList;
import org.compiere.util.TimeUtil;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

@Service
public class ManualInvoiceService
{
	private final IPricingBL pricingBL = Services.get(IPricingBL.class);
	private final IOrgDAO orgDAO = Services.get(IOrgDAO.class);
	private final IBPartnerDAO bPartnerDAO = Services.get(IBPartnerDAO.class);
	private final ITaxDAO taxDAO = Services.get(ITaxDAO.class);
	private final IDocumentBL documentBL = Services.get(IDocumentBL.class);
	private final IPriceListBL priceListBL = Services.get(IPriceListBL.class);
	private final ICurrencyBL currencyBL = Services.get(ICurrencyBL.class);

	private final InvoiceAcctService invoiceAcctService;
	private final CustomColumnService customColumnService;
	private final ManualInvoiceRepository manualInvoiceRepository;

	public ManualInvoiceService(
			@NonNull final InvoiceAcctService invoiceAcctService,
			@NonNull final CustomColumnService customColumnService,
			@NonNull final ManualInvoiceRepository manualInvoiceRepository)
	{
		this.invoiceAcctService = invoiceAcctService;
		this.customColumnService = customColumnService;
		this.manualInvoiceRepository = manualInvoiceRepository;
	}

	@NonNull
	public ManualInvoice createInvoice(@NonNull final CreateInvoiceRequest request)
	{
		final CreateManualInvoiceRequest createManualInvoiceRequest = buildCreateManualInvoiceRequest(request);

		final ManualInvoice invoice = manualInvoiceRepository.save(createManualInvoiceRequest);
		final InvoiceId invoiceId = invoice.getInvoiceId();
		final CreateInvoiceRequestHeader requestHeader = request.getHeader();

		saveCustomColumns(request, invoice);

		final CurrencyPrecision currencyPrecision = currencyBL.getStdPrecision(invoice.getCurrencyId());

		requestHeader.getGrandTotal(currencyPrecision)
				.ifPresent(invoice::validateGrandTotal);

		requestHeader.getTaxTotal(currencyPrecision)
				.ifPresent(invoice::validateTaxTotal);

		handleAcctData(request, invoice);

		if (request.getCompleteIt())
		{
			documentBL.processEx(manualInvoiceRepository.getRecordByIdNotNull(invoiceId), IDocument.ACTION_Complete);
		}

		return invoice;
	}

	private void saveCustomColumns(
			@NonNull final CreateInvoiceRequest request,
			@NonNull final ManualInvoice invoice)
	{
		Optional.ofNullable(request.getHeader().getExtendedProps())
				.ifPresent(props -> saveInvoiceCustomColumns(invoice.getInvoiceId(), props));

		request.getLines()
				.stream()
				.filter(line -> line.getExtendedProps() != null)
				.forEach(line -> {
					final InvoiceLineId invoiceLineId = invoice.getRepoIdByExternalLineId(line.getExternalLineId());

					saveInvoiceLineCustomColumns(invoiceLineId, line.getExtendedProps());
				});
	}

	private void saveInvoiceLineCustomColumns(
			@NonNull final InvoiceLineId invoiceLineId,
			@NonNull final Map<String, Object> valuesByColumnName)
	{
		manualInvoiceRepository.applyAndSave(invoiceLineId,
											 (invoiceLineRecord) -> customColumnService.setCustomColumns(InterfaceWrapperHelper.getPO(invoiceLineRecord), valuesByColumnName));
	}

	private void saveInvoiceCustomColumns(
			@NonNull final InvoiceId invoiceId,
			@NonNull final Map<String, Object> valuesByColumnName)
	{
		manualInvoiceRepository.applyAndSave(invoiceId,
											 (invoiceRecord) -> customColumnService.setCustomColumns(InterfaceWrapperHelper.getPO(invoiceRecord), valuesByColumnName));
	}

	private void handleAcctData(
			@NonNull final CreateInvoiceRequest request,
			@NonNull final ManualInvoice invoice)
	{
		final ImmutableList<InvoiceAcctRule> rules = request.getLines()
				.stream()
				.filter(line -> line.getElementValueId() != null)
				.map(line -> buildInvoiceAcctRule(line.getElementValueId(),
												  invoice.getRepoIdByExternalLineId(line.getExternalLineId()),
												  request.getAcctSchema().getId(),
												  line.getProductAcctType()))
				.collect(ImmutableList.toImmutableList());

		if (rules.isEmpty())
		{
			return;
		}

		final InvoiceAcct invoiceAcct = InvoiceAcct.builder()
				.invoiceId(invoice.getInvoiceId())
				.orgId(invoice.getOrgId())
				.rules(rules)
				.build();

		invoiceAcctService.save(invoiceAcct);
	}

	@NonNull
	private CreateManualInvoiceRequest buildCreateManualInvoiceRequest(@NonNull final CreateInvoiceRequest request)
	{
		final CreateInvoiceRequestHeader requestHeader = request.getHeader();
		final CountryId countryId = bPartnerDAO.getCountryId(requestHeader.getBillBPartnerLocationId());
		final BPartnerLocationAndCaptureId bPartnerLocationAndCaptureId = getBPartnerLocationAndCaptureId(requestHeader.getBillBPartnerLocationId());
		final ZoneId zoneId = orgDAO.getTimeZone(requestHeader.getOrgId());
		final PriceListId priceListId = getPriceListId(requestHeader, countryId, zoneId);

		final CreateManualInvoiceRequest.CreateManualInvoiceRequestBuilder createManualInvoiceRequestBuilder = CreateManualInvoiceRequest.builder()
				.orgId(requestHeader.getOrgId())
				.billBPartnerLocationId(requestHeader.getBillBPartnerLocationId())
				.billContactId(requestHeader.getBillContactId())
				.priceListId(priceListId)
				.dateInvoiced(requestHeader.getDateInvoiced())
				.dateAcct(requestHeader.getDateAcct())
				.dateOrdered(requestHeader.getDateOrdered())
				.externalHeaderId(requestHeader.getExternalHeaderId())
				.docTypeId(requestHeader.getDocTypeId())
				.poReference(requestHeader.getPoReference())
				.soTrx(requestHeader.getSoTrx())
				.currencyId(requestHeader.getCurrencyId());

		final ImmutableList<CreateManualInvoiceLineRequest> lines = request.getLines()
				.stream()
				.map(requestLine -> buildManualInvoiceLine(request,
														   requestLine,
														   priceListId,
														   countryId,
														   bPartnerLocationAndCaptureId,
														   zoneId))
				.collect(ImmutableList.toImmutableList());

		return createManualInvoiceRequestBuilder
				.lines(lines)
				.build();
	}

	@NonNull
	private CreateManualInvoiceLineRequest buildManualInvoiceLine(
			@NonNull final CreateInvoiceRequest request,
			@NonNull final CreateInvoiceRequestLine requestLine,
			@NonNull final PriceListId priceListId,
			@NonNull final CountryId countryId,
			@NonNull final BPartnerLocationAndCaptureId bPartnerLocationAndCaptureId,
			@NonNull final ZoneId zoneId)
	{
		final IPricingResult pricingResult = getPricingResult(request.getHeader(),
															  requestLine,
															  priceListId,
															  countryId,
															  zoneId);

		final ProductPrice manualPrice = request.getPriceEntered(requestLine).orElse(null);

		final CreateManualInvoiceLineRequest.CreateManualInvoiceLineRequestBuilder requestBuilder = CreateManualInvoiceLineRequest.builder()
				.externalLineId(requestLine.getExternalLineId())
				.line(requestLine.getLine())
				.lineDescription(requestLine.getLineDescription())

				.productId(requestLine.getProductId())
				.qtyToInvoice(requestLine.getQtyToInvoice())
				.taxId(getTaxId(request.getHeader(),
								requestLine,
								pricingResult,
								countryId,
								bPartnerLocationAndCaptureId));

		if (manualPrice != null)
		{
			final Money adjustedManualPrice = Optional.ofNullable(pricingResult.getPrecision())
					.map(precision -> manualPrice.toMoney().round(precision))
					.orElseGet(manualPrice::toMoney);

			requestBuilder
					.isManualPrice(true)
					.priceEntered(adjustedManualPrice)
					.priceUomId(manualPrice.getUomId());
		}
		else
		{
			requestBuilder
					.isManualPrice(false)
					.priceEntered(pricingResult.getPriceStdAsMoney())
					.priceUomId(pricingResult.getPriceUomId());
		}

		return requestBuilder.build();
	}

	@NonNull
	private TaxId getTaxId(
			@NonNull final CreateInvoiceRequestHeader header,
			@NonNull final CreateInvoiceRequestLine requestLine,
			@NonNull final IPricingResult pricingResult,
			@NonNull final CountryId countryId,
			@NonNull final BPartnerLocationAndCaptureId bPartnerLocationAndCaptureId)
	{
		return Optional.ofNullable(taxDAO.getBy(TaxQuery.builder()
														.fromCountryId(countryId)
														.orgId(header.getOrgId())
														.bPartnerLocationId(bPartnerLocationAndCaptureId)
														.dateOfInterest(TimeUtil.asTimestamp(header.getDateInvoiced()))
														.taxCategoryId(pricingResult.getTaxCategoryId())
														.soTrx(header.getSoTrx())
														.vatCodeId(requestLine.getVatCodeId())
														.build()))
				.map(Tax::getTaxId)
				.orElseThrow(() -> new AdempiereException("No TaxID found for TaxCode or BPartnerID!")
						.appendParametersToMessage()
						.setParameter("VatCodeId", requestLine.getVatCodeId())
						.setParameter("BPartnerID", header.getBillBPartnerId())
						.setParameter("BPartnerLocationID", header.getBillBPartnerLocationId()));
	}

	@NonNull
	private IPricingResult getPricingResult(
			@NonNull final CreateInvoiceRequestHeader header,
			@NonNull final CreateInvoiceRequestLine requestLine,
			@NonNull final PriceListId priceListId,
			@NonNull final CountryId countryId,
			@NonNull final ZoneId zoneId)
	{
		final IEditablePricingContext editablePricingContext = pricingBL.createInitialContext(header.getOrgId(),
																							  requestLine.getProductId(),
																							  header.getBillBPartnerId(),
																							  requestLine.getQtyToInvoice(),
																							  header.getSoTrx());

		editablePricingContext.setPriceListId(priceListId);

		final LocalDate dateInvoiced = header.getDateInvoiced().atZone(zoneId).toLocalDate();

		editablePricingContext.setPriceDate(dateInvoiced);
		editablePricingContext.setCountryId(countryId);

		return pricingBL.calculatePrice(editablePricingContext);
	}

	@NonNull
	private PriceListId getPriceListId(
			@NonNull final CreateInvoiceRequestHeader header,
			@NonNull final CountryId countryId,
			@NonNull final ZoneId zoneId)
	{
		final PricingSystemId pricingSystemId = Optional.ofNullable(bPartnerDAO.retrievePricingSystemIdOrNull(header.getBillBPartnerId(), header.getSoTrx()))
				.orElseThrow(() -> new AdempiereException("No PricingSystem found for billBPartner!")
						.appendParametersToMessage()
						.setParameter("BillBPartnerId", header.getBillBPartnerId()));

		final ZonedDateTime dateInvoiced = header.getDateInvoiced().atZone(zoneId);

		final I_M_PriceList priceListRecord = Optional.ofNullable(priceListBL.getCurrentPricelistOrNull(
						pricingSystemId,
						countryId,
						dateInvoiced,
						header.getSoTrx()))
				.orElseThrow(() -> new AdempiereException("No PriceList found for PricingSystem!")
						.appendParametersToMessage()
						.setParameter("PricingSystemId", pricingSystemId)
						.setParameter("CountryId", countryId)
						.setParameter("DateInvoiced", dateInvoiced)
						.setParameter("SOTrx", header.getSoTrx()));

		return PriceListId.ofRepoId(priceListRecord.getM_PriceList_ID());
	}

	@NonNull
	private static InvoiceAcctRule buildInvoiceAcctRule(
			@NonNull final ElementValueId elementValueId,
			@NonNull final InvoiceLineId invoiceLineId,
			@NonNull final AcctSchemaId acctSchemaId,
			@Nullable final ProductAcctType productAcctType)
	{
		return InvoiceAcctRule.builder()
				.elementValueId(elementValueId)
				.matcher(InvoiceAcctRuleMatcher.builder()
								 .invoiceLineId(invoiceLineId)
								 .acctSchemaId(acctSchemaId)
								 .accountTypeName(Optional.ofNullable(productAcctType)
														  .map(ProductAcctType::getColumnName)
														  .map(AccountTypeName::ofColumnName)
														  .orElse(null))
								 .build())
				.build();
	}

	@NonNull
	private BPartnerLocationAndCaptureId getBPartnerLocationAndCaptureId(@NonNull final BPartnerLocationId billBPartnerLocationId)
	{
		final LocationId locationId = bPartnerDAO.getLocationId(billBPartnerLocationId);
		return BPartnerLocationAndCaptureId.of(billBPartnerLocationId, locationId);
	}
}

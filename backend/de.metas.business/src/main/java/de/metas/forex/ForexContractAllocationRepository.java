package de.metas.forex;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import de.metas.money.CurrencyId;
import de.metas.money.Money;
import de.metas.order.OrderId;
import de.metas.util.Services;
import lombok.NonNull;
import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.compiere.model.I_C_ForeignExchangeContract_Alloc;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public class ForexContractAllocationRepository
{
	private final IQueryBL queryBL = Services.get(IQueryBL.class);

	public ForexContractAllocation create(@NonNull final ForexContractAllocateRequest request)
	{
		final I_C_ForeignExchangeContract_Alloc record = InterfaceWrapperHelper.newInstance(I_C_ForeignExchangeContract_Alloc.class);
		record.setC_ForeignExchangeContract_ID(request.getForexContractId().getRepoId());
		record.setC_Order_ID(request.getOrderId().getRepoId());
		record.setC_Currency_ID(request.getAmountToAllocate().getCurrencyId().getRepoId());
		record.setAmount(request.getAmountToAllocate().toBigDecimal());
		record.setGrandTotal(request.getOrderGrandTotal().toBigDecimal());
		InterfaceWrapperHelper.save(record);

		return fromRecord(record);
	}

	private static ForexContractAllocation fromRecord(final I_C_ForeignExchangeContract_Alloc record)
	{
		return ForexContractAllocation.builder()
				.id(ForexContractAllocationId.ofRepoId(record.getC_ForeignExchangeContract_Alloc_ID()))
				.contractId(ForexContractId.ofRepoId(record.getC_ForeignExchangeContract_ID()))
				.orderId(OrderId.ofRepoId(record.getC_Order_ID()))
				.amount(extractAmount(record))
				.build();
	}

	@NonNull
	private static Money extractAmount(final I_C_ForeignExchangeContract_Alloc record)
	{
		return Money.of(record.getAmount(), CurrencyId.ofRepoId(record.getC_Currency_ID()));
	}

	public Money computeAllocatedAmount(@NonNull final ForexContractId contractId, @NonNull final CurrencyId expectedCurrencyId)
	{
		final ImmutableMap<CurrencyId, Money> amountsByCurrencyId = queryBL
				.createQueryBuilder(I_C_ForeignExchangeContract_Alloc.class)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_C_ForeignExchangeContract_Alloc.COLUMNNAME_C_ForeignExchangeContract_ID, contractId)
				.create()
				.stream()
				.map(ForexContractAllocationRepository::extractAmount)
				.collect(Money.sumByCurrency());

		return singleCurrency(amountsByCurrencyId, expectedCurrencyId);
	}

	public Money computeAllocatedAmount(@NonNull final OrderId orderId, @NonNull final CurrencyId expectedCurrencyId)
	{
		final ImmutableMap<CurrencyId, Money> amountsByCurrencyId = queryBL
				.createQueryBuilder(I_C_ForeignExchangeContract_Alloc.class)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_C_ForeignExchangeContract_Alloc.COLUMNNAME_C_Order_ID, orderId)
				.create()
				.stream()
				.map(ForexContractAllocationRepository::extractAmount)
				.collect(Money.sumByCurrency());

		return singleCurrency(amountsByCurrencyId, expectedCurrencyId);
	}

	@NonNull
	private static Money singleCurrency(@NonNull final ImmutableMap<CurrencyId, Money> amountsByCurrencyId, @NonNull final CurrencyId expectedCurrencyId)
	{
		if (amountsByCurrencyId.isEmpty())
		{
			return Money.zero(expectedCurrencyId);
		}
		else if (amountsByCurrencyId.size() == 1)
		{
			final Money amount = amountsByCurrencyId.get(expectedCurrencyId);
			if (amount == null)
			{
				throw new AdempiereException("Allocation amount has invalid currency: " + amountsByCurrencyId);
			}
			return amount;
		}
		else
		{
			// shall not happen
			throw new AdempiereException("Allocation in multiple currencies is not allowed: " + amountsByCurrencyId);
		}
	}

	public ImmutableSet<ForexContractId> getContractIdsByOrderIds(@NonNull final Set<OrderId> orderIds)
	{
		if (orderIds.isEmpty())
		{
			return ImmutableSet.of();
		}

		final List<ForexContractId> contractIds = queryBL.createQueryBuilder(I_C_ForeignExchangeContract_Alloc.class)
				.addOnlyActiveRecordsFilter()
				.addInArrayFilter(I_C_ForeignExchangeContract_Alloc.COLUMNNAME_C_Order_ID, orderIds)
				.create()
				.listDistinct(I_C_ForeignExchangeContract_Alloc.COLUMNNAME_C_ForeignExchangeContract_ID, ForexContractId.class);
		return ImmutableSet.copyOf(contractIds);
	}

	public boolean hasAllocations(@NonNull final ForexContractId contractId)
	{
		return queryBL.createQueryBuilder(I_C_ForeignExchangeContract_Alloc.class)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_C_ForeignExchangeContract_Alloc.COLUMNNAME_C_ForeignExchangeContract_ID, contractId)
				.create()
				.anyMatch();
	}

	public boolean hasAllocations(@NonNull final OrderId orderId)
	{
		return queryBL.createQueryBuilder(I_C_ForeignExchangeContract_Alloc.class)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_C_ForeignExchangeContract_Alloc.COLUMNNAME_C_Order_ID, orderId)
				.create()
				.anyMatch();
	}
}

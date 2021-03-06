package live.lingting.virtual.currency.bitcoin;

import android.util.Log;

import static live.lingting.virtual.currency.bitcoin.util.BTCUtils.PROPERTY_PREFIX;
import static org.bitcoinj.core.Transaction.Purpose;
import static org.bitcoinj.core.Transaction.SigHash;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.SignatureDecodeException;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.script.ScriptPattern;
import org.bouncycastle.util.encoders.Hex;
import live.lingting.virtual.currency.bitcoin.contract.OmniContract;
import live.lingting.virtual.currency.bitcoin.endpoints.BitcoinCypherEndpoints;
import live.lingting.virtual.currency.bitcoin.endpoints.BitcoinEndpoints;
import live.lingting.virtual.currency.bitcoin.endpoints.BlockchainEndpoints;
import live.lingting.virtual.currency.bitcoin.endpoints.OmniEndpoints;
import live.lingting.virtual.currency.bitcoin.model.FeeAndSpent;
import live.lingting.virtual.currency.bitcoin.model.Unspent;
import live.lingting.virtual.currency.bitcoin.model.blockchain.LatestBlock;
import live.lingting.virtual.currency.bitcoin.model.blockchain.RawTransaction;
import live.lingting.virtual.currency.bitcoin.model.cypher.Balance;
import live.lingting.virtual.currency.bitcoin.model.omni.Balances;
import live.lingting.virtual.currency.bitcoin.model.omni.Domain;
import live.lingting.virtual.currency.bitcoin.model.omni.PushTx;
import live.lingting.virtual.currency.bitcoin.model.omni.TokenHistory;
import live.lingting.virtual.currency.bitcoin.model.omni.TransactionByHash;
import live.lingting.virtual.currency.bitcoin.properties.BitcoinProperties;
import live.lingting.virtual.currency.bitcoin.util.BTCUtils;
import live.lingting.virtual.currency.core.Contract;
import live.lingting.virtual.currency.core.Endpoints;
import live.lingting.virtual.currency.core.PlatformService;
import live.lingting.virtual.currency.core.enums.TransactionStatus;
import live.lingting.virtual.currency.core.enums.VirtualCurrencyPlatform;
import live.lingting.virtual.currency.core.model.Account;
import live.lingting.virtual.currency.core.model.TransactionInfo;
import live.lingting.virtual.currency.core.model.TransferParams;
import live.lingting.virtual.currency.core.model.TransferResult;
import live.lingting.virtual.currency.core.util.AbiUtils;

/**
 * @author lingting 2020-09-01 17:16
 */
@Slf4j
public class BitcoinServiceImpl implements PlatformService<BitcoinTransactionGenerate> {

	/**
	 * ???????????????????????????
	 */
	public static final String FLAG = ".";

	@Getter
	private static final Map<String, Integer> CONTRACT_DECIMAL_CACHE = new ConcurrentHashMap<>();

	/**
	 * ????????????of?????????????????????
	 */
	private static final TransactionByHash STATIC_TRANSACTION_HASH = new TransactionByHash();

	/**
	 * ????????????of?????????????????????
	 */
	private static final Balances STATIC_BALANCES = new Balances();

	/**
	 * ????????????of?????????????????????
	 */
	private static final TokenHistory STATIC_TOKEN_HISTORY = new TokenHistory();

	private final BitcoinProperties properties;

	private final NetworkParameters np;

	private final BlockchainEndpoints blockchainEndpoints;

	private static final OmniEndpoints OMNI_ENDPOINTS = OmniEndpoints.MAINNET;

	private final BitcoinCypherEndpoints cypherEndpoints;

	public BitcoinServiceImpl(BitcoinProperties properties) {
		this.properties = properties;
		this.np = properties.getNp();

		if (properties.getEndpoints() == BitcoinEndpoints.MAINNET) {
			blockchainEndpoints = BlockchainEndpoints.MAINNET;
			cypherEndpoints = BitcoinCypherEndpoints.MAINNET;
		}
		else {
			blockchainEndpoints = BlockchainEndpoints.TEST;
			cypherEndpoints = BitcoinCypherEndpoints.TEST;
		}

	}

	@Override
	public Optional<TransactionInfo> getTransactionByHash(String hash) throws Exception {
		RawTransaction rawTransaction = RawTransaction.of(blockchainEndpoints, hash);

		if (rawTransaction == null || StrUtil.isBlank(rawTransaction.getHash())
				|| CollectionUtil.isEmpty(rawTransaction.getOuts())) {
			return Optional.empty();
		}

		// ?????????????????????, ?????????btc
		if (!rawTransaction.getResponse().contains(PROPERTY_PREFIX)) {
			return btcTransactionHandler(rawTransaction);
		}
		// ??????????????????, ????????????
		boolean isBtc = true;

		// ???????????????
		BigInteger sumOut = BigInteger.ZERO;
		// ????????????
		Map<String, BigDecimal> outInfos = new HashMap<>(rawTransaction.getOuts().size());

		// ????????????, ??????????????? btc??????
		for (RawTransaction.Out out : rawTransaction.getOuts()) {
			String script = out.getScript();
			// ?????????????????????
			if (script.startsWith(PROPERTY_PREFIX)
					// ????????? 44
					&& script.length() == 44) {
				isBtc = false;
				break;
			}
			// ??????????????????, ?????????btc??????, ????????????????????????
			sumOut = statisticsDetails(sumOut, outInfos, out);
		}

		// btc ????????????
		if (isBtc) {
			return btcTransactionHandler(sumOut, outInfos, rawTransaction);
		}

		TransactionByHash response = request(STATIC_TRANSACTION_HASH, OMNI_ENDPOINTS, hash);
		// ?????????????????? ?????? valid ??? false
		if (response.getAmount() == null || !response.getValid()) {
			return Optional.empty();
		}

		OmniContract contract = OmniContract.getById(response.getPropertyId());
		TransactionInfo transactionInfo = new TransactionInfo()

				.setContract(contract != null ? contract : AbiUtils.createContract(response.getPropertyId().toString()))

				.setBlock(response.getBlock())

				.setHash(hash)

				.setValue(response.getAmount())

				.setVirtualCurrencyPlatform(VirtualCurrencyPlatform.BITCOIN)

				.setTime(response.getBlockTime())

				.setFrom(response.getSendingAddress())

				.setTo(response.getReferenceAddress())

				.setStatus(
						// ???????????? ????????????????????? ????????????,????????????
						response.getConfirmations().compareTo(BigInteger.valueOf(properties.getConfirmationsMin())) >= 0
								? TransactionStatus.SUCCESS : TransactionStatus.WAIT);
		return Optional.of(transactionInfo);
	}

	@Override
	public Integer getDecimalsByContract(Contract contract) throws JsonProcessingException {
		if (contract == null) {
			return 0;
		}

		if (contract.getDecimals() != null) {
			return contract.getDecimals();
		}

		if (CONTRACT_DECIMAL_CACHE.containsKey(contract.getHash())) {
			return CONTRACT_DECIMAL_CACHE.get(contract.getHash());
		}

		TokenHistory history = request(STATIC_TOKEN_HISTORY, OMNI_ENDPOINTS, contract.getHash());
		int decimals = getDecimalsByString(history.getTransactions().get(0).getAmount());
		CONTRACT_DECIMAL_CACHE.put(contract.getHash(), decimals);
		return decimals;
	}

	@Override
	public BigInteger getBalanceByAddressAndContract(String address, Contract contract) throws JsonProcessingException {
		if (contract == OmniContract.BTC) {
			Balance balance = Balance.of(cypherEndpoints, address);
			if (balance == null || StrUtil.isNotBlank(balance.getError()) || balance.getFinalBalance() == null) {
				return BigInteger.ZERO;
			}
			return balance.getFinalBalance();
		}
		Balances balances = request(STATIC_BALANCES, OMNI_ENDPOINTS, address);
		if (CollectionUtil.isEmpty(balances.getBalance())) {
			return BigInteger.ZERO;
		}
		for (Balances.Balance balance : balances.getBalance()) {
			// ??????????????????
			if (!CONTRACT_DECIMAL_CACHE.containsKey(balance.getId())) {
				CONTRACT_DECIMAL_CACHE.put(contract.getHash(),
						getDecimalsByString(balance.getPropertyInfo().getTotalTokens()));
			}

			if (balance.getId().equals(contract.getHash())) {
				return balance.getValue();
			}
		}
		return BigInteger.ZERO;
	}

	@Override
	public BigDecimal getNumberByBalanceAndContract(BigInteger balance, Contract contract, MathContext mathContext)
			throws JsonProcessingException {
		if (contract == null) {
			return new BigDecimal(balance);
		}
		if (balance == null) {
			return BigDecimal.ZERO;
		}
		// ???????????????
		return new BigDecimal(balance).divide(BigDecimal.TEN.pow(getDecimalsByContract(contract)), mathContext);
	}

	@Override
	public BitcoinTransactionGenerate transactionGenerate(Account from, String to, Contract contract, BigDecimal value,
			TransferParams params) throws Exception {
		if (value.compareTo(BigDecimal.ZERO) <= 0) {
			return BitcoinTransactionGenerate.failed("????????????????????????0!");
		}
		// BTC ????????????
		Coin btcAmount;
		// ??????????????????
		BigInteger contractAmount = BigInteger.ZERO;
		// ????????????
		if (contract == OmniContract.BTC) {
			btcAmount = BTCUtils.btcToCoin(value);
		}
		// ????????????
		else {
			// ????????????????????????
			btcAmount = Coin.valueOf(546);
		}

		// ??????????????? ?????? ?????????????????????
		if (params.getSumFee() == null) {
			params.setFee(params.getFee() == null ? properties.feeByByte.get() : params.getFee());
		}

		// ???????????????, ???????????? , ???????????????
		FeeAndSpent fs = FeeAndSpent.of(
				// ??????
				this,
				// ??????
				contract,
				// ??????
				params,
				// ???????????????
				properties.getUnspent().apply(from.getAddress(), properties.getEndpoints()),
				// ????????????
				btcAmount,
				// ???????????????
				new BigInteger(properties.getConfirmationsMin().toString()));

		// ????????????
		org.bitcoinj.core.Transaction tx = new org.bitcoinj.core.Transaction(np);

		// ????????????
		Address fromAddress = Address.fromString(np, from.getAddress());
		// ????????????
		Address toAddress = Address.fromString(np, to);
		// ??????????????????
		tx.addOutput(btcAmount, toAddress);

		// ????????????
		boolean zero = fs.getZero();
		if (zero) {
			tx.addOutput(
					// ?????? = ???????????? - ???????????? - ????????????
					fs.getOutNumber().subtract(fs.getFee()).subtract(btcAmount),
					// ???????????????
					fromAddress);
		}

		// ??????????????????
		if (contract != OmniContract.BTC) {
			contractAmount = valueToBalanceByContract(value, contract);
			// ????????????hex
			String contractHex = StrUtil.format("{}{}{}",
					// ???????????? ???????????????
					PROPERTY_PREFIX,
					// ??????hash ??? ???????????? ?????????0 ??? 16???
					StrUtil.padPre(new BigInteger(contract.getHash()).toString(16), 16, "0"),
					// ???????????? ??? ???????????? ?????????0 ??? 16???
					StrUtil.padPre(contractAmount.toString(16), 16, "0"));

			// ????????????
			tx.addOutput(Coin.ZERO, new Script(Utils.HEX.decode(contractHex)));
		}

		// ????????????
		for (int i = 0; i < fs.getList().size(); i++) {
			Unspent spent = fs.getList().get(i);
			TransactionOutPoint outPoint = new TransactionOutPoint(np, spent.getOut(),
					Sha256Hash.wrap(spent.getHash()));


			TransactionInput input = new TransactionInput(np, tx,
					Hex.decode(spent.getScript())
					, outPoint,
					Coin.valueOf(spent.getValue().longValue()));
			tx.addInput(input);
		}
		return BitcoinTransactionGenerate.success(from, to,
				// ??????????????????
				contract != OmniContract.BTC ? contractAmount : BTCUtils.coinToBtcBalance(btcAmount), contract,
				new BitcoinTransactionGenerate.Bitcoin(tx, fs.getFee()));
	}

	@Override
	public BitcoinTransactionGenerate transactionSign(BitcoinTransactionGenerate generate)
			throws SignatureDecodeException {
		// ????????????????????????????????????
		boolean error = !generate.getSuccess();
		if (error) {
			return generate;
		}
		org.bitcoinj.core.Transaction tx = generate.getBitcoin().getTransaction();
		Account from = generate.getFrom();

		// ????????????
		List<ECKey> keys = getEcKeysByFrom(from);
		// ????????????
		for (int inputIndex = 0; inputIndex < tx.getInputs().size(); inputIndex++) {
			TransactionInput txIn = tx.getInput(inputIndex);
			Script script = txIn.getScriptSig();
			// ?????? ????????? p2sh?????? ?????? ??? ???????????????
			boolean isNativeP2sh = from.getMulti()
					&& (!generate.getBitcoin().getFirstSign() || ScriptPattern.isP2SH(script));
			if (isNativeP2sh) {
				List<TransactionSignature> signatures;
				signatures = new ArrayList<>(from.getMultiNum());

				// ??????????????????????????????
				if (!generate.getBitcoin().getFirstSign()) {
					Iterator<ScriptChunk> sci = txIn.getScriptSig().getChunks().iterator();

					while (sci.hasNext()) {
						ScriptChunk sc = sci.next();
						// ????????? op code ??????0
						if (sc.opcode != 0) {
							// ????????????????????????, ????????????????????????
							if (sci.hasNext()) {
								// ????????????
								TransactionSignature signature = TransactionSignature.decodeFromBitcoin(sc.data, true,
										true);
								signatures.add(signature);
							}
						}
					}
				}
				script = ScriptBuilder.createMultiSigOutputScript(from.getMultiNum(), keys);
				for (ECKey ecKey : keys) {
					// ??????????????????????????????????????????????????????, ?????????????????????
					if (signatures.size() == from.getMultiNum()) {
						continue;
					}
					if (ecKey.hasPrivKey()) {
						signatures.add(new TransactionSignature(
								// ??????
								ecKey.sign(
										// ??????hash
										tx.hashForSignature(inputIndex, script, SigHash.ALL, false)),
								SigHash.ALL, false)

						);
					}
				}

				Script scriptSig = ScriptBuilder.createP2SHMultiSigInputScript(signatures, script);
				txIn.setScriptSig(scriptSig);
				continue;
			}

			ECKey key = keys.get(0);

			// p2sh-p2wpkh
			if (ScriptPattern.isP2SH(script)) {
				// ??????
				Script redeemScript = ScriptBuilder.createP2WPKHOutputScript(key);
				Script witnessScript = ScriptBuilder.createP2PKHOutputScript(key);

				TransactionSignature signature = tx.calculateWitnessSignature(inputIndex,
						key,
						witnessScript,
						txIn.getValue(), SigHash.ALL, false);

				txIn.setWitness(TransactionWitness.redeemP2WPKH(signature, key));
				txIn.setScriptSig(new ScriptBuilder().data(redeemScript.getProgram()).build());
				continue;
			}

			if (ScriptPattern.isP2WPKH(script)) {
				script = ScriptBuilder.createP2PKHOutputScript(key);
				TransactionSignature signature = tx.calculateWitnessSignature(inputIndex, key, script, txIn.getValue(),
						SigHash.ALL, false);
				txIn.setScriptSig(ScriptBuilder.createEmpty());
				txIn.setWitness(TransactionWitness.redeemP2WPKH(signature, key));
				continue;
			}

			TransactionSignature txSignature = tx.calculateSignature(inputIndex, key, script, SigHash.ALL, false);

			if (ScriptPattern.isP2PK(script)) {
				txIn.setScriptSig(ScriptBuilder.createInputScript(txSignature));
			}
			else if (ScriptPattern.isP2PKH(script)) {
				txIn.setScriptSig(ScriptBuilder.createInputScript(txSignature, key));
			}
			else {
				return BitcoinTransactionGenerate.failed("?????????????????????!");
			}
		}

		// ??????
		tx.verify();
		// ???????????????
		Context.getOrCreate(np);
		// ????????????
		tx.getConfidence().setSource(TransactionConfidence.Source.SELF);

		tx.setPurpose(Purpose.USER_PAYMENT);
		// ?????????????????????hex?????????
		String raw = Hex.toHexString(tx.bitcoinSerialize());
		generate.setSignHex(raw);
		return generate;
	}

	@Override
	public TransferResult transactionBroadcast(BitcoinTransactionGenerate generate) throws Exception {
		// ????????????????????????????????????
		boolean error = !generate.getSuccess();
		if (error) {
			return TransferResult.failed(generate);
		}
		// ????????????, ?????? ??????hash
		PushTx pushTx = properties.getBroadcastTransaction().apply(generate.getSignHex(), OMNI_ENDPOINTS);
		if (!pushTx.isSuccess()) {
			if (pushTx.getE() != null) {
				return TransferResult.failed(pushTx.getE());
			}
			return TransferResult.failed("????????????");
		}
		return TransferResult.success(pushTx.getTxId());
	}

	@SneakyThrows
	@Override
	public boolean validate(String address) {
		Balance balance = Balance.of(cypherEndpoints, address);
		return StrUtil.isBlank(balance.getError());
	}

	/**
	 * ?????? str ????????????
	 *
	 * @author lingting 2020-12-14 13:51
	 */
	private int getDecimalsByString(String str) {
		if (!str.contains(FLAG)) {
			return 0;
		}
		return str.substring(str.indexOf(FLAG)).length() - 1;
	}

	/**
	 * ????????????,?????????????????????,???????????????, ??????5???
	 * @return ??????: ??????
	 * @author lingting 2020-12-14 16:38
	 */
	public long sleepTime() {
		return TimeUnit.SECONDS.toMillis(5);
	}

	/**
	 * ????????????
	 *
	 * @author lingting 2020-12-14 16:46
	 */
	private <T> T request(Domain<T> domain, Endpoints endpoints, Object params) throws JsonProcessingException {
		// ?????????
		boolean lock = properties.getLock().get();
		if (lock) {
			try {
				// ??????????????????
				return domain.of(endpoints, params);
			}
			finally {
				// ?????????
				properties.getUnlock().get();
			}
		}
		// ??????, ??????????????????
		ThreadUtil.sleep(sleepTime());
		return request(domain, endpoints, params);
	}

	/**
	 * ????????????????????????, ????????????
	 * @author lingting 2021-01-10 19:00
	 */
	private Optional<TransactionInfo> btcTransactionHandler(RawTransaction rawTransaction) throws Exception {
		// ???????????????
		BigInteger sumOut = BigInteger.ZERO;
		// ????????????
		Map<String, BigDecimal> outInfos = new HashMap<>(rawTransaction.getOuts().size());

		// ????????????
		for (RawTransaction.Out out : rawTransaction.getOuts()) {
			// ??????????????????
			sumOut = statisticsDetails(sumOut, outInfos, out);
		}
		return btcTransactionHandler(sumOut, outInfos, rawTransaction);
	}

	private Optional<TransactionInfo> btcTransactionHandler(BigInteger sumOut, Map<String, BigDecimal> outInfos,
			RawTransaction rawTransaction) throws Exception {
		// ???????????????
		BigInteger sumIn = BigInteger.ZERO;
		// ????????????
		Map<String, BigDecimal> inInfos = new HashMap<>(rawTransaction.getIns().size());

		// ????????????
		for (RawTransaction.In in : rawTransaction.getIns()) {
			sumIn = statisticsDetails(sumIn, inInfos, in.getPrevOut());
		}
		// ????????? = ?????? - ?????? ????????? btc
		BigDecimal fee = getNumberByBalanceAndContract(sumIn.subtract(sumOut), OmniContract.BTC);

		TransactionInfo transactionInfo = new TransactionInfo().setContract(OmniContract.BTC)

				.setBlock(rawTransaction.getBlockHeight())

				.setHash(rawTransaction.getHash())

				.setVirtualCurrencyPlatform(VirtualCurrencyPlatform.BITCOIN)

				.setTime(rawTransaction.getTime())
				// btc ??????
				.setBtcInfo(new TransactionInfo.BtcInfo(inInfos, outInfos, fee));

		// ????????????
		if (rawTransaction.getBlockHeight() == null) {
			// ??????
			transactionInfo.setStatus(TransactionStatus.WAIT);
		}
		// ???????????????
		else {
			// ??????????????????
			LatestBlock block = LatestBlock.of(blockchainEndpoints);
			// ???????????????
			BigInteger confirmationNumber = block.getHeight().subtract(transactionInfo.getBlock());
			transactionInfo.setStatus(
					// ???????????? ????????????????????? ????????????,????????????
					confirmationNumber.compareTo(BigInteger.valueOf(properties.getConfirmationsMin())) >= 0
							? TransactionStatus.SUCCESS : TransactionStatus.WAIT);
		}
		return Optional.of(transactionInfo);
	}

	/**
	 * ??????????????????
	 * @author lingting 2021-01-10 19:31
	 */
	private BigInteger statisticsDetails(BigInteger sumIn, Map<String, BigDecimal> inInfos, RawTransaction.Out out)
			throws Exception {
		// ??????????????????
		sumIn = sumIn.add(out.getValue());
		// ??????????????????
		if (inInfos.containsKey(out.getAddr())) {
			inInfos.put(out.getAddr(),
					inInfos.get(out.getAddr()).add(getNumberByBalanceAndContract(out.getValue(), OmniContract.BTC)));
		}
		// ?????????????????????
		else {
			inInfos.put(out.getAddr(), getNumberByBalanceAndContract(out.getValue(), OmniContract.BTC));
		}
		return sumIn;
	}

	private List<ECKey> getEcKeysByFrom(Account from) {
		List<ECKey> keys;
		// ??????
		if (from.getMulti()) {
			keys = new ArrayList<>(from.getPublicKeyArray().size());
			List<String> publicKeyArray = from.getPublicKeyArray();
			for (int keyIndex = 0; keyIndex < publicKeyArray.size(); keyIndex++) {
				// ????????????
				if (StrUtil.isBlank(from.getPrivateKeyArray().get(keyIndex))) {
					keys.add(ECKey.fromPublicOnly(Hex.decode(publicKeyArray.get(keyIndex))));
				}
				// ???????????????
				else {
					ECKey ecKey = ECKey.fromPrivate(Hex.decode(from.getPrivateKeyArray().get(keyIndex)));
					keys.add(ecKey);
				}
			}
		}
		// ??????
		else {
			keys = ListUtil.toList(ECKey.fromPrivate(Hex.decode(from.getPrivateKey())));
		}
		return keys;
	}

}

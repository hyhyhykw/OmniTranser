package live.lingting.virtual.currency.bitcoin.model;

import cn.hutool.http.HttpRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import live.lingting.virtual.currency.bitcoin.endpoints.BitcoinEndpoints;
import live.lingting.virtual.currency.bitcoin.endpoints.BitcoinSochainEndpoints;
import live.lingting.virtual.currency.bitcoin.endpoints.BlockchainEndpoints;
import live.lingting.virtual.currency.bitcoin.model.blockchain.BlockchainUnspentRes;
import live.lingting.virtual.currency.core.Endpoints;
import live.lingting.virtual.currency.core.util.JacksonUtils;

/**
 * @author lingting 2021/1/7 11:19
 */
@NoArgsConstructor
@Accessors(chain = true)
public abstract class UnspentRes {

	/**
	 * 获取指定地址未使用utxo
	 * @param bitcoinEndpoints 节点
	 * @param min 最小确认数, 部分节点, 此参数无效
	 * @param address 地址
	 * @return live.lingting.virtual.currency.bitcoin.model.UnspentRes
	 * @author lingting 2021-01-08 18:40
	 */
	public static UnspentRes of(BitcoinEndpoints bitcoinEndpoints, int min, String address)
			throws JsonProcessingException {
		// 测试节点使用 sochain
		boolean isSochain = bitcoinEndpoints == BitcoinEndpoints.TEST;

		Endpoints endpoints = !isSochain ? BlockchainEndpoints.MAINNET :
				BitcoinSochainEndpoints.TEST;

		HttpRequest request;
		// sochain 节点处理
		request = HttpRequest.get(endpoints.getHttpUrl("addrs/"
				// 地址
				+ address+"?token=" +"5ed2381ec1ba4bb0a25f5f0a6bec8a10"+ "&unspentOnly=true&includeScript=true"

		));

		String response = request.execute().body();

		if (response.equals(BlockchainUnspentRes.ERROR)) {
			return new BlockchainUnspentRes().setUnspentList(new ArrayList<>());
		}
		return JacksonUtils.toObj(response, BlockchainUnspentRes.class);
	}

	public abstract List<Unspent> toUnspentList();

}

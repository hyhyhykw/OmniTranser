package live.lingting.virtual.currency.bitcoin.model;

import android.util.Log;

import org.bitcoinj.core.Coin;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import live.lingting.virtual.currency.bitcoin.BitcoinServiceImpl;
import live.lingting.virtual.currency.bitcoin.contract.OmniContract;
import live.lingting.virtual.currency.bitcoin.util.BTCUtils;
import live.lingting.virtual.currency.core.Contract;
import live.lingting.virtual.currency.core.exception.InsufficientBalanceException;
import live.lingting.virtual.currency.core.model.TransferParams;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import static live.lingting.virtual.currency.bitcoin.util.BTCUtils.getSumFee;

/**
 * 手续费和使用的余额
 *
 * @author lingting 2021-01-07 15:08
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeeAndSpent {

    /**
     * 总手续费
     */
    private Coin fee;

    private Coin outNumber;

    private List<Unspent> list;

    private Boolean zero = false;

    public static FeeAndSpent of(BitcoinServiceImpl service, Contract contract, TransferParams params,
                                 List<Unspent> unspentList, Coin amount, BigInteger min) throws Exception {
        // 记录本次转账使用的 spent
        List<Unspent> useList = new ArrayList<>();
        // 转出数量
        Coin outNumber = Coin.ZERO;
        // 总手续费(不找零)
        Coin sumFee = Coin.ZERO;
        // 是否找零
        boolean isZero = false;
        // 基础输出数量
        int baseOutNumber = 1;
        // 如果不是 btc 转账
        if (contract != OmniContract.BTC) {
            // 则至少两笔输出 一笔输出 546Coin 作为载体, 一笔输出合约
            baseOutNumber = 2;
        }

        // 筛选未使用btc
        for (Unspent unspent : unspentList) {
            // 如果确认数低于最小确认数, 不使用
            if (unspent.getConfirmations().compareTo(min) < 0) {
                continue;
            }

            // 计算手续费(不找零)
            Coin coin = getSumFee(useList.size(), baseOutNumber, params);
            // 计算 转账数量+手续费(不找零)
            Coin number = amount.add(coin);
            // 如果转出数量 大于等于 转账数量+手续费(不找零)
            if (outNumber.compareTo(number) >= 0) {
                // 计算手续费(找零)
                Coin zeroCoin = zeroNumber(params, coin);
                // 如果转出数量 大于 转账数量+手续费(找零)
                if (outNumber.compareTo(amount.add(zeroCoin)) > 0) {
                    // 找零
                    isZero = true;
                    // 配置总手续费
                    sumFee = zeroCoin;
                }
                // 如果转出数量 小于等于 转账数量+手续费(找零)
                else {
                    // 不找零
                    isZero = false;
                    // 配置总手续费 = 转出数量 - 转账数量
                    sumFee = outNumber.subtract(amount);
                }
                // 足够交易, 结束
                break;
            }

            // 如果转出数量 小于等于 转账数量+手续费(不找零), 需要继续添加
            useList.add(unspent);
            outNumber = outNumber.add(
                    // 转为 聪
                    BTCUtils.btcToCoin(
                            // 交易数量转为 个btc
                            service.getNumberByBalanceAndContract(
                                    // 数量
                                    unspent.getValue(),
                                    // 合约
                                    contract)));

        }

        // 如果手续费为0, 表示没有进行手续费判断
        if (sumFee.compareTo(Coin.ZERO) == 0) {
            // 计算手续费(不找零)
            Coin coin = getSumFee(useList.size(), baseOutNumber, params);

            Log.e("TAG", "coin====>" + coin.value);
            Log.e("TAG", "outNumber====>" + outNumber.value);
            Log.e("TAG", "amount====>" + amount.value);
            // 如果转出数量 大于等于 转账数量 + 手续费(不找零)
            if (outNumber.compareTo(coin.add(amount)) >= 0) {
                // 计算手续费(找零)
                Coin zeroCoin = zeroNumber(params, coin);
                // 如果转出数量 大于 转账数量+手续费(找零)
                if (outNumber.compareTo(zeroCoin.add(amount)) > 0) {
                    // 找零
                    isZero = true;
                    // 配置总手续费
                    sumFee = zeroCoin;
                }
                // 如果转出数量 小于等于 转账数量+手续费(找零)
                else {
                    // 不找零
                    isZero = false;
                    // 配置总手续费 = 转出数量 - 转账数量
                    sumFee = outNumber.subtract(amount);
                }
            }
            // 小于
            else {
                throw new InsufficientBalanceException(coin);
            }
        }

        return new FeeAndSpent(sumFee, outNumber, useList, isZero);
    }

    /**
     * 根据条件计算找零手续费
     *
     * @param params 参数
     * @param coin   不找零手续费
     * @return org.bitcoinj.core.Coin
     * @author lingting 2021-01-10 15:18
     */
    public static Coin zeroNumber(TransferParams params, Coin coin) {
        // 已配置总手续费
        if (params.getSumFee() != null) {
            return params.getSumFee();
        }
        // 未配置
        else {
            // 增加一个输出相当于增加 34 字节, 所以手续费等于 34* params.getFee() + coin
            return coin.add(params.getFee().multiply(34));
        }
    }

}

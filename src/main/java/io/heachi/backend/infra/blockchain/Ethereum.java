package io.heachi.backend.infra.blockchain;

import io.heachi.backend.infra.blockchain.base.Blockchain;
import io.heachi.backend.infra.blockchain.base.WalletInfo;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.crypto.Wallet;
import org.web3j.crypto.WalletFile;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.admin.Admin;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;
import org.web3j.utils.Convert.Unit;
import org.web3j.utils.Numeric;

@Component
@Slf4j
public class Ethereum implements Blockchain {

  @Value("{ethereum.url}")
  private String nodeUrl = "https://tn.henesis.io/ethereum/ropsten?clientId=815fcd01324b8f75818a755a72557750";
  private final Admin admin = Admin.build(new HttpService(nodeUrl));
  private final Web3j web3j = Web3j.build(new HttpService(nodeUrl));
  private BigInteger latestBlockNumber = null;

  @Override
  public WalletInfo createWallet() {
    String seed = UUID.randomUUID().toString();

    try {
      ECKeyPair ecKeyPair = Keys.createEcKeyPair();
      BigInteger privateKeyInDec = ecKeyPair.getPrivateKey();

      String privateKey = privateKeyInDec.toString(16);

      WalletFile walletFile = Wallet.createLight(seed, ecKeyPair);
      String address = "0x" + walletFile.getAddress();

      log.info("create wallet {address: {}, privateKey: {}}", address, privateKey);

      return new WalletInfo(address, privateKey);
    } catch (Exception e) {
      log.error("ethereum >> create wallet >> fail");
    }
    return null;
  }

  @Override
  public BigInteger getBalance(String address) throws IOException {
    EthGetBalance balance = web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send();
    return balance.getBalance();
  }

  @Override
  public String transfer(String address, String privateKey, String toHash, BigDecimal eth) {
    try {
      BigInteger value = Convert.toWei(eth.toString(), Convert.Unit.ETHER).toBigInteger();
      EthGetTransactionCount transactionCount = web3j.ethGetTransactionCount(address,
              DefaultBlockParameterName.LATEST)
          .send();

      log.info("transaction count: {}", transactionCount.getTransactionCount());

      EthBlock lastBlock = web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)
          .send();
      BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice()
          .multiply(BigInteger.valueOf(20));
      BigInteger gasLimit = Convert.toWei(lastBlock.getBlock().getGasLimit().toString(), Unit.GWEI)
          .toBigInteger();

      RawTransaction rawTransaction = RawTransaction.createEtherTransaction(
          transactionCount.getTransactionCount(),
          gasPrice,
          BigInteger.valueOf(21000),
          toHash,
          value);

      byte[] signedMessage = TransactionEncoder.signMessage(
          rawTransaction,
          Credentials.create(privateKey));

      EthSendTransaction transaction = web3j.ethSendRawTransaction(
          Numeric.toHexString(signedMessage)).send();

      if (transaction.getError() != null) {
        log.error(transaction.getError().getMessage());
        log.error("code {}", transaction.getError().getCode());
      }

      return transaction.getTransactionHash();
    } catch (Exception e) {
      log.error("ethereum >> transfer >> fail");
      e.printStackTrace();
    }
    return null;
  }

  public Disposable subscribeBlock(Consumer<EthBlock> consumer) {
    return web3j.blockFlowable(true)
        .subscribe(ethBlock -> {
              latestBlockNumber = ethBlock.getBlock().getNumber();
              consumer.accept(ethBlock);
            },
            throwable -> log.error("subscribeBlock Throwable {}", throwable.getMessage()));
  }

  public BigInteger getLatestBlockNumber() {
    if (latestBlockNumber == null) {
      try {
        latestBlockNumber = web3j.ethBlockNumber().send().getBlockNumber();
      } catch (IOException e) {
        log.error("get block number >> error");
      }
    }

    return latestBlockNumber;
  }

  public Optional<Transaction> findTransaction(String hash) throws IOException {
    return web3j.ethGetTransactionByHash(hash).send().getTransaction();
  }

  public Disposable subscribePastBlock(BigInteger startBlockNumber, Consumer<EthBlock> consumer) {
    DefaultBlockParameterNumber number = new DefaultBlockParameterNumber(
        startBlockNumber);
    return web3j.replayPastBlocksFlowable(number, DefaultBlockParameterName.LATEST
        , true).subscribe(consumer,
        throwable -> log.error("subscribePastBlock Throwable {}", throwable.getMessage()));
  }
}
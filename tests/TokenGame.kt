import org.ethereum.crypto.ECKey
import org.ethereum.solidity.compiler.CompilationResult
import org.ethereum.solidity.compiler.SolidityCompiler
import org.ethereum.util.blockchain.StandaloneBlockchain
import org.ethereum.util.blockchain.SolidityContract
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TokenGame {
    companion object {
        val compiledContract by lazy {
            val compilerResult = SolidityCompiler.compile(File("contracts/tokengame.sol"), true, SolidityCompiler.Options.ABI, SolidityCompiler.Options.BIN, SolidityCompiler.Options.INTERFACE, SolidityCompiler.Options.METADATA)
            assertFalse(compilerResult.isFailed, compilerResult.errors)
            CompilationResult.parse(compilerResult.output).contracts.mapKeys { Regex("^.*:(.*)$").find(it.key)!!.groups[1]!!.value }
        }
        val token by lazy {
            compiledContract["Token"]!!
        }
        val excessWithdraw by lazy {
            compiledContract["ExcessWithdraw"]!!
        }
        val tokenDist by lazy {
            compiledContract["TokenDistribution"]!!
        }
        val prizePot by lazy {
            compiledContract["PrizePot"]!!
        }
        val tokenGame by lazy {
            compiledContract["TokenGame"]!!
        }
        val alice = ECKey()
        val bob = ECKey()
        val carol = ECKey()
        val dan = ECKey()
    }

    lateinit var blockchain: StandaloneBlockchain
    lateinit var dist: SolidityContract
    lateinit var dist_token: SolidityContract
    val aliceAddress get() = BigInteger(1, alice.address)
    val bobAddress get() = BigInteger(1, bob.address)
    val carolAddress get() = BigInteger(1, carol.address)
    val danAddress get() = BigInteger(1, dan.address)

    @Before
    fun `setup`() {
        blockchain = StandaloneBlockchain()
                .withAutoblock(true)
                .withAccountBalance(alice.address, BigInteger.valueOf(2).pow(128))
                .withAccountBalance(bob.address, BigInteger.valueOf(2).pow(128))
                .withAccountBalance(carol.address, BigInteger.ZERO)
                .withAccountBalance(dan.address, BigInteger.ONE)
        blockchain.createBlock()
        blockchain.sender = alice
        dist = blockchain.submitNewContract(tokenDist, 1, 1, 1000000L) // target and cap are 1 wei
        val dist_token_addr = dist.callConstFunction("token")[0] as ByteArray
        dist_token = blockchain.createExistingContractFromABI(token.abi, dist_token_addr)
    }

    fun fast_forward_past_end_time() {
        val end_time = dist.callConstFunction("end_time")[0] as BigInteger
        blockchain = blockchain.withCurrentTime(Date(end_time.toLong()*1000L))
        val block = blockchain.createBlock();
        assertTrue(block.header.timestamp > end_time.toLong())
    }

    fun contribute(sender: ECKey, amount: Long, lock_weeks: Int): Boolean {
        blockchain.sender = sender
        return dist.callFunction(amount, "contribute", lock_weeks).isSuccessful
    }

    fun close_next_bucket(sender: ECKey): Boolean {
        blockchain.sender = sender
        return dist.callFunction("close_next_bucket").isSuccessful
    }

    fun claim_tokens(sender: ECKey, player: ECKey, bucket: String): Boolean {
        blockchain.sender = sender
        return dist.callFunction("claim_tokens", player.address, BigInteger(bucket)).isSuccessful
    }

    fun withdraw_from_bucket(sender: ECKey, bucket: String): Boolean {
        val excess_withdraw_addr = dist.callConstFunction("excess_withdraws", BigInteger(bucket))[0] as ByteArray
        val excess_withdraw = blockchain.createExistingContractFromABI(excessWithdraw.abi, excess_withdraw_addr)
        val excess_token_addr = dist.callConstFunction("excess_tokens", BigInteger(bucket))[0] as ByteArray
        val excess_token = blockchain.createExistingContractFromABI(token.abi, excess_token_addr)
        blockchain.sender = sender
        val approveResult = excess_token.callFunction("approve", excess_withdraw_addr, BigInteger("1000000"))
        assertTrue(approveResult.isSuccessful)
        val withdrawResult = excess_withdraw.callFunction("withdraw")
        assertTrue(withdrawResult.isSuccessful)
        return approveResult.isSuccessful && withdrawResult.isSuccessful
    }

    @Test
    fun `game token creation`() {
        assertTrue(contribute(bob, 1000000L, 0))
        assertEquals(BigInteger("1000000"), dist.callConstFunction("total_wei_given")[0] as BigInteger)
        assertEquals(BigInteger("1000000"), blockchain.blockchain.repository.getBalance(dist.address))
    }

    @Test
    fun `strangers cannot mint`() {
        blockchain.sender = bob
        val mintResult1 = dist_token.callFunction("mint", bob.address, BigInteger.ONE)
        assertFalse(mintResult1.isSuccessful)
    }

    @Test
    fun `contribute after end time`() {
        fast_forward_past_end_time()
        assertFalse(contribute(bob, 1000000L, 0))
    }

    @Test
    fun `close bucket before end time`() {
        assertFalse(close_next_bucket(bob))
    }

    @Test
    fun `close bucket zero`() {
        assertTrue(contribute(bob, 1000000L, 0))
        fast_forward_past_end_time()
        val bob_balance_before = blockchain.blockchain.repository.getBalance(bob.address)
        assertTrue(close_next_bucket(bob))
        val bob_balance_after = blockchain.blockchain.repository.getBalance(bob.address)
        assertEquals(BigInteger.ZERO, dist.callConstFunction("last_bucket_closed")[0] as BigInteger)
        // 1M gas to be paid to iterate through 100 buckets down to number 0 and close zero bucket
        assertEquals(BigInteger("1030415"), (bob_balance_before - bob_balance_after)/BigInteger("50000000000"))
        assertTrue(claim_tokens(bob, bob, "0"))
        assertTrue(withdraw_from_bucket(bob, "0"))
    }
}

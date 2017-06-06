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
        val eva = ECKey()
    }

    lateinit var blockchain: StandaloneBlockchain
    lateinit var dist: SolidityContract
    lateinit var dist_token: SolidityContract
    val aliceAddress get() = BigInteger(1, alice.address)
    val bobAddress get() = BigInteger(1, bob.address)
    val carolAddress get() = BigInteger(1, carol.address)
    val danAddress get() = BigInteger(1, dan.address)
    val evaAddress get() = BigInteger(1, eva.address)

    @Before
    fun `setup`() {
        blockchain = StandaloneBlockchain()
                .withAutoblock(true)
                .withAccountBalance(alice.address, BigInteger.valueOf(2).pow(128))
                .withAccountBalance(bob.address, BigInteger.valueOf(2).pow(128))
                .withAccountBalance(carol.address, BigInteger.ZERO)
                .withAccountBalance(dan.address, BigInteger.ONE)
                .withAccountBalance(eva.address, BigInteger.valueOf(2).pow(128))
        blockchain.createBlock()
        blockchain.sender = alice
        dist = blockchain.submitNewContract(tokenDist, 900, 1000, 1000000L) // target and cap are 1 wei
        val dist_token_addr = dist.callConstFunction("token")[0] as ByteArray
        dist_token = blockchain.createExistingContractFromABI(token.abi, dist_token_addr)
    }

    fun fast_forward_past_end_time(weeks: Long) {
        val end_time = dist.callConstFunction("end_time")[0] as BigInteger
        blockchain = blockchain.withCurrentTime(Date(end_time.toLong()*1000L + weeks*7L*24L*3600L*1000L))
        val block = blockchain.createBlock();
        assertTrue(block.header.timestamp > end_time.toLong())
    }

    fun fast_forward_to_before_end_time() {
        val end_time = dist.callConstFunction("end_time")[0] as BigInteger
        // 60 seconds before the end_time
        blockchain = blockchain.withCurrentTime(Date(end_time.toLong()*1000L - 60L*1000L))
        val block = blockchain.createBlock();
        assertTrue(block.header.timestamp < end_time.toLong())
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
        val withdrawResult = excess_withdraw.callFunction("withdraw")
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
        fast_forward_past_end_time(0)
        assertFalse(contribute(bob, 1000000L, 0))
    }

    @Test
    fun `close bucket before end time`() {
        assertFalse(close_next_bucket(bob))
    }

    @Test
    fun `close bucket zero`() {
        assertTrue(contribute(bob, 1000000L, 0))
        fast_forward_past_end_time(0)
        val bob_balance_before = blockchain.blockchain.repository.getBalance(bob.address)
        assertTrue(close_next_bucket(bob))
        val bob_balance_after = blockchain.blockchain.repository.getBalance(bob.address)
        assertEquals(BigInteger.ZERO, dist.callConstFunction("last_bucket_closed")[0] as BigInteger)
        // 1M gas to be paid to iterate through 100 buckets down to number 0 and close zero bucket
        assertEquals(BigInteger("1020480"), (bob_balance_before - bob_balance_after)/BigInteger("50000000000"))
        // Various intermediate checks
        assertEquals(BigInteger("1000000"), dist.callConstFunction("contributions", bob.address, BigInteger("0"))[0] as BigInteger)
        assertEquals(BigInteger("1000"), dist.callConstFunction("total_wei_accepted")[0] as BigInteger)
        assertEquals(BigInteger("1000"), dist.callConstFunction("wei_accepted_from_bucket", BigInteger("0"))[0] as BigInteger)
        assertEquals(BigInteger("1000000"), dist.callConstFunction("wei_given_to_bucket", BigInteger("0"))[0] as BigInteger)
        assertTrue(claim_tokens(bob, bob, "0"))
        assertTrue(withdraw_from_bucket(bob, "0"))
    }

    @Test
    fun `bucket zero vs bucket hundred`() {
        assertTrue(contribute(bob, 1000000L, 0))
        assertTrue(contribute(eva, 1000000L, 100))
        assertEquals(BigInteger("1000000"), dist.callConstFunction("wei_given_to_bucket", BigInteger("0"))[0] as BigInteger)
        assertEquals(BigInteger("1000000"), dist.callConstFunction("wei_given_to_bucket", BigInteger("100"))[0] as BigInteger)
        fast_forward_past_end_time(0)
        assertTrue(close_next_bucket(bob))
        assertEquals(BigInteger("100"), dist.callConstFunction("last_bucket_closed")[0] as BigInteger)
        assertEquals(BigInteger("2"), dist.callConstFunction("cap_remainder")[0] as BigInteger)
        assertTrue(close_next_bucket(bob))
        assertEquals(BigInteger.ZERO, dist.callConstFunction("last_bucket_closed")[0] as BigInteger)
        // Various intermediate checks
        assertEquals(BigInteger("1000000"), dist.callConstFunction("contributions", bob.address, BigInteger("0"))[0] as BigInteger)
        assertEquals(BigInteger("1000"), dist.callConstFunction("total_wei_accepted")[0] as BigInteger)
        assertEquals(BigInteger("2"), dist.callConstFunction("wei_accepted_from_bucket", BigInteger("0"))[0] as BigInteger)
        assertEquals(BigInteger.ZERO, dist.callConstFunction("contributions", eva.address, BigInteger("0"))[0] as BigInteger)
        assertEquals(BigInteger("1000000"), dist.callConstFunction("contributions", eva.address, BigInteger("100"))[0] as BigInteger)
        assertEquals(BigInteger("998"), dist.callConstFunction("wei_accepted_from_bucket", BigInteger("100"))[0] as BigInteger)
        assertTrue(claim_tokens(bob, bob, "0"))
        assertTrue(withdraw_from_bucket(bob, "0"))
        // Eva tries to withdraw
        assertTrue(claim_tokens(eva, eva, "100"))
        assertFalse(withdraw_from_bucket(eva, "100"))
        // Wait for 100 weeks
        fast_forward_past_end_time(99)
        assertFalse(withdraw_from_bucket(eva, "100"))
        fast_forward_past_end_time(100)
        assertTrue(withdraw_from_bucket(eva, "100"))
        // Compare tokens awarded to bob and to eva
        assertEquals(BigInteger("2000"), dist_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger)
        assertEquals(BigInteger("998000"), dist_token.callConstFunction("balanceOf", eva.address)[0] as BigInteger)
    }

    @Test
    fun `extend end time`() {
        assertTrue(contribute(bob, 1000000L, 0))
        assertEquals(BigInteger("1000000"), dist.callConstFunction("ema")[0] as BigInteger)
        val end_time_1 = dist.callConstFunction("end_time")[0] as BigInteger
        fast_forward_to_before_end_time()
        assertTrue(contribute(eva, 1000000L, 0))
        assertEquals(BigInteger("1000000"), dist.callConstFunction("ema")[0] as BigInteger)
        val end_time_2 = dist.callConstFunction("end_time")[0] as BigInteger
        assertEquals(BigInteger("86366"), end_time_2 - end_time_1)
        fast_forward_to_before_end_time()
        assertTrue(contribute(bob, 1000000L, 0))
        assertEquals(BigInteger("1062473"), dist.callConstFunction("ema")[0] as BigInteger)
        val end_time_3 = dist.callConstFunction("end_time")[0] as BigInteger
        assertEquals(BigInteger("61164"), end_time_3 - end_time_2)
        fast_forward_to_before_end_time()
        assertTrue(contribute(eva, 1000000L, 0))
        assertEquals(BigInteger("1149029"), dist.callConstFunction("ema")[0] as BigInteger)
        val end_time_4 = dist.callConstFunction("end_time")[0] as BigInteger
        assertEquals(BigInteger("49604"), end_time_4 - end_time_3)
        fast_forward_to_before_end_time()
        assertTrue(contribute(bob, 1000000L, 0))
        assertEquals(BigInteger("1233556"), dist.callConstFunction("ema")[0] as BigInteger)
        val end_time_5 = dist.callConstFunction("end_time")[0] as BigInteger
        assertEquals(BigInteger("42597"), end_time_5 - end_time_4)
        fast_forward_to_before_end_time()
        assertTrue(contribute(bob, 1000000L, 0))
        assertEquals(BigInteger("1313972"), dist.callConstFunction("ema")[0] as BigInteger)
        val end_time_6 = dist.callConstFunction("end_time")[0] as BigInteger
        assertEquals(BigInteger("37808"), end_time_6 - end_time_5)
    }

    @Test
    fun `multiple contributions to the same bucket`() {
        assertTrue(contribute(bob, 1000000L, 0))
        assertTrue(contribute(bob, 2000000L, 0))
        assertEquals(BigInteger("3000000"), dist.callConstFunction("contributions", bob.address, BigInteger("0"))[0] as BigInteger)
    }

    @Test
    fun `multiple contributions to different buckets`() {
        assertTrue(contribute(bob, 1000000L, 0))
        assertTrue(contribute(bob, 1000000L, 1))
        fast_forward_past_end_time(0)
        assertTrue(close_next_bucket(alice))
        assertTrue(close_next_bucket(alice))
        assertTrue(claim_tokens(alice, bob, "0"))
        assertTrue(claim_tokens(alice, bob, "1"))
        // Get all the tokens as the sole participant
        assertEquals(BigInteger("1000000"), dist_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger)
    }

    @Test
    fun `did not reach the target`() {
        val alice_balance_before = blockchain.blockchain.repository.getBalance(alice.address)
        assertTrue(contribute(bob, 800L, 0))
        fast_forward_past_end_time(0)
        assertTrue(close_next_bucket(bob))
        assertTrue(claim_tokens(bob, bob, "0"))
        val alice_balance_after = blockchain.blockchain.repository.getBalance(alice.address)
        assertEquals(BigInteger.ZERO, alice_balance_after - alice_balance_before)
    }

    @Test
    fun `reached the target`() {
        val alice_balance_before = blockchain.blockchain.repository.getBalance(alice.address)
        assertTrue(contribute(bob, 100000L, 0))
        fast_forward_past_end_time(0)
        assertTrue(close_next_bucket(bob))
        assertTrue(claim_tokens(bob, bob, "0"))
        val alice_balance_after = blockchain.blockchain.repository.getBalance(alice.address)
        assertEquals(BigInteger("1000"), alice_balance_after - alice_balance_before)
    }

    @Test
    fun `contribute gas cost`() {
        assertTrue(contribute(bob, 100000L, 0))
        blockchain.createBlock()
        blockchain.createBlock()
        blockchain.createBlock()
        blockchain.createBlock()
        blockchain.createBlock()
        blockchain.createBlock()
        val bob_balance_before = blockchain.blockchain.repository.getBalance(bob.address)
        assertTrue(contribute(bob, 100000L, 0))
        val bob_balance_after = blockchain.blockchain.repository.getBalance(bob.address)
        val gas = (bob_balance_before - bob_balance_after - BigInteger("1000000"))/BigInteger("50000000000")
        assertEquals(BigInteger("57278"), gas)
    }

    @Test
    fun `time extension cap`() {
        assertTrue(contribute(bob, 1000000L, 0))
        val end_time_1 = dist.callConstFunction("end_time")[0] as BigInteger
        fast_forward_to_before_end_time()
        // Contribute 100 times more
        assertTrue(contribute(eva, 100000000L, 0))
        val end_time_2 = dist.callConstFunction("end_time")[0] as BigInteger
        // Extension time is capped - one day
        assertEquals(BigInteger("171055"), end_time_2 - end_time_1)
    }
}

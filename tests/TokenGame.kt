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
    lateinit var prize_pot: SolidityContract
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
                .withAccountBalance(carol.address, BigInteger.valueOf(2).pow(128))
                .withAccountBalance(dan.address, BigInteger.ONE)
                .withAccountBalance(eva.address, BigInteger.valueOf(2).pow(128))
        blockchain.createBlock()
        blockchain.sender = alice
        dist = blockchain.submitNewContract(tokenDist, 900, 1000, 1000000L) // target is 900 wei, cap is 1000 wei
        prize_pot = blockchain.submitNewContract(prizePot, dist.address)
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

    fun escape(sender: ECKey, lock_weeks: Int): Boolean {
        blockchain.sender = sender
        return dist.callFunction("escape", lock_weeks).isSuccessful
    }

    fun close_next_bucket(sender: ECKey): Boolean {
        blockchain.sender = sender
        return dist.callFunction("close_next_bucket").isSuccessful
    }

    fun claim_tokens(sender: ECKey, player: ECKey, bucket: Int): Boolean {
        blockchain.sender = sender
        return dist.callFunction("claim_tokens", player.address, bucket).isSuccessful
    }

    fun withdraw_from_bucket(sender: ECKey, bucket: Int): Boolean {
        val excess_withdraw_addr = dist.callConstFunction("excess_withdraws", bucket)[0] as ByteArray
        val excess_withdraw = blockchain.createExistingContractFromABI(excessWithdraw.abi, excess_withdraw_addr)
        val excess_token_addr = dist.callConstFunction("excess_tokens", bucket)[0] as ByteArray
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
        assertEquals(BigInteger("1029808"), (bob_balance_before - bob_balance_after)/BigInteger("50000000000"))
        // Various intermediate checks
        assertEquals(BigInteger("1000000"), dist.callConstFunction("contributions", bob.address, BigInteger("0"))[0] as BigInteger)
        assertEquals(BigInteger("1000"), dist.callConstFunction("total_wei_accepted")[0] as BigInteger)
        assertEquals(BigInteger("1000"), dist.callConstFunction("wei_accepted_from_bucket", BigInteger("0"))[0] as BigInteger)
        assertEquals(BigInteger("1000000"), dist.callConstFunction("wei_given_to_bucket", BigInteger("0"))[0] as BigInteger)
        assertTrue(claim_tokens(bob, bob, 0))
        assertTrue(withdraw_from_bucket(bob, 0))
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
        assertTrue(claim_tokens(bob, bob, 0))
        assertTrue(withdraw_from_bucket(bob, 0))
        // Eva tries to withdraw
        assertTrue(claim_tokens(eva, eva, 100))
        assertFalse(withdraw_from_bucket(eva, 100))
        // Wait for 100 weeks
        fast_forward_past_end_time(99)
        assertFalse(withdraw_from_bucket(eva, 100))
        fast_forward_past_end_time(100)
        assertTrue(withdraw_from_bucket(eva, 100))
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
        assertTrue(claim_tokens(alice, bob, 0))
        assertTrue(claim_tokens(alice, bob, 1))
        // Get all the tokens as the sole participant
        assertEquals(BigInteger("1000000"), dist_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger)
    }

    @Test
    fun `did not reach the target`() {
        val alice_balance_before = blockchain.blockchain.repository.getBalance(alice.address)
        assertTrue(contribute(bob, 800L, 0))
        fast_forward_past_end_time(0)
        assertFalse(close_next_bucket(bob))
        assertFalse(claim_tokens(bob, bob, 0))
        assertTrue(escape(bob, 0))
        val alice_balance_after = blockchain.blockchain.repository.getBalance(alice.address)
        assertEquals(BigInteger.ZERO, alice_balance_after - alice_balance_before)
    }

    @Test
    fun `reached the target`() {
        val alice_balance_before = blockchain.blockchain.repository.getBalance(alice.address)
        assertTrue(contribute(bob, 100000L, 0))
        fast_forward_past_end_time(0)
        assertTrue(close_next_bucket(bob))
        assertTrue(claim_tokens(bob, bob, 0))
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
        assertEquals(BigInteger("57406"), gas)
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

    @Test
    fun `attempt to escape and recycle`() {
        assertTrue(contribute(bob, 1000000L, 100))
        assertEquals(BigInteger("1000000"), dist.callConstFunction("ema")[0] as BigInteger)
        fast_forward_to_before_end_time()
        assertTrue(escape(bob, 100))
        assertEquals(BigInteger("1000000"), dist.callConstFunction("ema")[0] as BigInteger)
        val end_time_1 = dist.callConstFunction("end_time")[0] as BigInteger
        assertTrue(contribute(bob, 1000000L, 100))
        assertEquals(BigInteger("1000000"), dist.callConstFunction("ema")[0] as BigInteger)
        val end_time_2 = dist.callConstFunction("end_time")[0] as BigInteger
        assertEquals(BigInteger("0"), end_time_2 - end_time_1) // Recycling escaped funds does not extend
        assertTrue(contribute(eva, 1000000L, 0))
        assertEquals(BigInteger("1000000"), dist.callConstFunction("ema")[0] as BigInteger)
        val end_time_3 = dist.callConstFunction("end_time")[0] as BigInteger
        assertEquals(BigInteger("86392"), end_time_3 - end_time_2) // After that, the usual extension logic applies
    }

    @Test
    fun `escape gas cost`() {
        assertTrue(contribute(bob, 1000000L, 0))
        val bob_balance_before = blockchain.blockchain.repository.getBalance(bob.address)
        assertTrue(escape(bob, 0))
        val bob_balance_after = blockchain.blockchain.repository.getBalance(bob.address)
        val gas = (bob_balance_before - bob_balance_after + BigInteger("10000000"))/BigInteger("50000000000")
        assertEquals(BigInteger("22157"), gas)
    }

    @Test
    fun `cancel prize pot`() {
        blockchain.sender = eva
        blockchain.sendEther(prize_pot.address, BigInteger("1000000000"))
        assertEquals(BigInteger("1000000000"), blockchain.blockchain.repository.getBalance(prize_pot.address))
        assertTrue(contribute(bob, 800L, 0))
        fast_forward_past_end_time(0)
        assertFalse(close_next_bucket(alice))
        val alice_balance_before = blockchain.blockchain.repository.getBalance(alice.address)
        blockchain.sender = bob
        val result = prize_pot.callFunction("cancel")
        assertTrue(result.isSuccessful)
        val alice_balance_after = blockchain.blockchain.repository.getBalance(alice.address)
        assertEquals(BigInteger("1000000000"), alice_balance_after - alice_balance_before)
    }

    @Test
    fun `cannot escape after a bucket is closed`() {
        assertTrue(contribute(bob, 1000000L, 0))
        assertTrue(contribute(carol, 1000000L, 30))
        fast_forward_past_end_time(0)
        // Still possible to escape after the end time before the first bucket is closed
        assertTrue(escape(carol, 30))
        assertTrue(close_next_bucket(alice))
        // Cannot escape anymore
        assertFalse(escape(bob, 0))
    }

    @Test
    fun `cannot escape twice`() {
        assertTrue(contribute(bob, 1000000L, 9))
        assertTrue(escape(bob, 9))
        // Second escape in the same bucket
        assertFalse(escape(bob, 9))
    }

    @Test
    fun `escape from different buckets`() {
        assertTrue(contribute(bob, 1000000L, 3))
        assertTrue(contribute(bob, 2000000L, 4))
        assertTrue(contribute(bob, 3000000L, 5))
        assertTrue(escape(bob, 5))
        fast_forward_past_end_time(0)
        assertTrue(escape(bob, 4))
        assertTrue(escape(bob, 3))
    }

    @Test
    fun `claim prize from the prize pot`() {
        blockchain.sender = eva
        blockchain.sendEther(prize_pot.address, BigInteger("100000000000000000000"))
        assertTrue(contribute(bob, 100000L, 0))
        fast_forward_past_end_time(0)
        assertTrue(close_next_bucket(bob))
        assertFalse(close_next_bucket(bob))
        assertTrue(claim_tokens(bob, bob, 0))
        blockchain.sender = bob
        val approveResult = dist_token.callFunction("approve", prize_pot.address, BigInteger("1000000"))
        assertTrue(approveResult.isSuccessful)
        val bob_balance_before = blockchain.blockchain.repository.getBalance(bob.address)
        val claimResult = prize_pot.callFunction("claim_prize")
        assertTrue(claimResult.isSuccessful)
        val bob_balance_after = blockchain.blockchain.repository.getBalance(bob.address)
        // Prize minus gas cost
        assertEquals(BigInteger("99998225500000000000"), bob_balance_after-bob_balance_before)
    }

    @Test
    fun `alice bob carol example from blog`() {
        // cap is 1000 wei
        assertTrue(contribute(alice, 100, 52))
        assertTrue(contribute(bob, 1000, 4))
        assertTrue(contribute(carol, 10000, 0))
        fast_forward_past_end_time(0)
        assertTrue(close_next_bucket(carol))
        assertTrue(close_next_bucket(bob))
        assertTrue(close_next_bucket(alice))
        assertFalse(close_next_bucket(carol))
        assertTrue(claim_tokens(alice, carol, 0))
        assertTrue(claim_tokens(alice, bob, 4))
        assertTrue(claim_tokens(alice, alice, 52))
        assertEquals(BigInteger("100000"), dist_token.callConstFunction("balanceOf", alice.address)[0] as BigInteger)
        assertEquals(BigInteger("104000"), dist_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger)
        assertEquals(BigInteger("796000"), dist_token.callConstFunction("balanceOf", carol.address)[0] as BigInteger)
    }

    @Test
    fun `multiple contributions one escape`() {
        assertTrue(contribute(bob, 1000000000000000000L, 3))
        blockchain.createBlock()
        assertTrue(contribute(bob, 1000000000000000000L, 3))
        val bob_balance_before = blockchain.blockchain.repository.getBalance(bob.address)
        assertTrue(escape(bob, 3))
        val bob_balance_after = blockchain.blockchain.repository.getBalance(bob.address)
        // Two million wei minus gas cost
        assertEquals(BigInteger("1998890550000000000"), bob_balance_after-bob_balance_before)
    }

    @Test
    fun `two players two buckets`() {
        assertTrue(contribute(bob, 1000000000000000000L, 3))
        blockchain.createBlock()
        assertTrue(contribute(carol, 2000000000000000000L, 3))
        blockchain.createBlock()
        assertTrue(contribute(bob, 3000000000000000000L, 50))
        blockchain.createBlock()
        assertTrue(contribute(carol, 4000000000000000000L, 50))
        blockchain.createBlock()
        fast_forward_past_end_time(0)
        assertTrue(close_next_bucket(alice))
        assertEquals(BigInteger("50"), dist.callConstFunction("last_bucket_closed")[0] as BigInteger)
        assertTrue(close_next_bucket(alice))
        assertEquals(BigInteger("3"), dist.callConstFunction("last_bucket_closed")[0] as BigInteger)
        assertTrue(close_next_bucket(alice))
        assertFalse(close_next_bucket(alice))
        assertTrue(claim_tokens(carol, bob, 3))
        assertTrue(claim_tokens(bob, bob, 50))
        assertFalse(claim_tokens(carol, bob, 100))
        assertTrue(claim_tokens(bob, carol, 3))
        assertTrue(claim_tokens(carol, carol, 50))
        assertFalse(claim_tokens(bob, carol, 100))
    }

    @Test
    fun `lock up too long`() {
        assertFalse(contribute(bob, 1000000000000000000L, 101))
    }

    @Test
    fun `mint quantities`() {
        blockchain.sender = alice
        val a_token = blockchain.submitNewContract(token)
        val mintResult1 = a_token.callFunction("mint", bob.address, 1000000L)
        assertTrue(mintResult1.isSuccessful)
        assertEquals(BigInteger("1000000"), a_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger)
        assertEquals(BigInteger("1000000"), a_token.callConstFunction("totalSupply")[0] as BigInteger)
        blockchain.sender = bob
        val mintResult2 = a_token.callFunction("mint", bob.address, 1000000L)
        assertFalse(mintResult2.isSuccessful)
        blockchain.sender = alice
        val mintResult3 = a_token.callFunction("mint", bob.address, 2000000L)
        assertTrue(mintResult3.isSuccessful)
        assertEquals(BigInteger("3000000"), a_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger)
        assertEquals(BigInteger("3000000"), a_token.callConstFunction("totalSupply")[0] as BigInteger)
        val mintResult4 = a_token.callFunction("mint", carol.address, 4000000L)
        assertTrue(mintResult4.isSuccessful)
        assertEquals(BigInteger("3000000"), a_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger)
        assertEquals(BigInteger("4000000"), a_token.callConstFunction("balanceOf", carol.address)[0] as BigInteger)
        assertEquals(BigInteger("7000000"), a_token.callConstFunction("totalSupply")[0] as BigInteger) 
    }

    @Test
    fun `zero contributon`() {
        assertFalse(contribute(bob, 0, 3))
    }

    @Test
    fun `all buckets`() {
        blockchain.sender = eva
        blockchain.sendEther(prize_pot.address, BigInteger("100000000000000000000"))
        for (b in 0..100) {
            println(b)
            assertTrue(contribute(bob, 1000000000000000000L + 1000000000000000000L*b, b))
        }
        fast_forward_past_end_time(0)
        val carol_balance_before = blockchain.blockchain.repository.getBalance(carol.address)
        for (b in 0..100) {
            assertTrue(close_next_bucket(carol))
        }
        assertFalse(close_next_bucket(carol))
        val carol_balance_after = blockchain.blockchain.repository.getBalance(carol.address)
        val gas_cost_100_buckets = (carol_balance_before - carol_balance_after + BigInteger("10000000"))/BigInteger("50000000000")
        // At the gas price 20 GWei, it will cost 2.15 ETH to close 100 buckets
        assertEquals(BigInteger("107384358"), gas_cost_100_buckets)
        for (b in 0..100) {
            assertTrue(claim_tokens(alice, bob, b))
        }
        val bob_balance_before = blockchain.blockchain.repository.getBalance(bob.address)
        blockchain.sender = bob
        val approveResult = dist_token.callFunction("approve", prize_pot.address, 1000000000000000000L)
        assertTrue(approveResult.isSuccessful)
        val bob_balance_inter = blockchain.blockchain.repository.getBalance(bob.address)
        val claimResult = prize_pot.callFunction("claim_prize")
        assertTrue(claimResult.isSuccessful)
        val bob_balance_after = blockchain.blockchain.repository.getBalance(bob.address)
        // Prize exclusing gas cost of claiming
        assertEquals(BigInteger("99997475500000000000"), bob_balance_after - bob_balance_inter)
        assertEquals(BigInteger("99995291400000000000"), bob_balance_after - bob_balance_before)
    }

    @Test
    fun `withdraw before time and after time`() {
        blockchain.sender = alice
        val a_token = blockchain.submitNewContract(token)
        val mintResult = a_token.callFunction("mint", bob.address, BigInteger("100000000000000000000"))
        assertTrue(mintResult.isSuccessful)
        var block = blockchain.createBlock();
        // Withdraw only in 200 seconds
        val withdraw_time = block.header.timestamp + 200L
        val a_withdraw = blockchain.submitNewContract(excessWithdraw, withdraw_time, a_token.address)
        blockchain.sendEther(a_withdraw.address, BigInteger("100000000000000000000"))
        assertEquals(BigInteger("100000000000000000000"), blockchain.blockchain.repository.getBalance(a_withdraw.address))
        blockchain.sender = bob
        val approveResult = a_token.callFunction("approve", a_withdraw.address, BigInteger("100000000000000000000"))
        assertTrue(approveResult.isSuccessful)
        val bob_balance_before = blockchain.blockchain.repository.getBalance(bob.address)
        val bob_tokens_before = a_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger
        var withdrawResult1 = a_withdraw.callFunction("withdraw")
        assertFalse(withdrawResult1.isSuccessful)
        val bob_balance_1 = blockchain.blockchain.repository.getBalance(bob.address)
        val bob_tokens_1 = a_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger
        // Check token and ETH balance
        assertEquals(BigInteger("100000000000000000000"), bob_tokens_before)
        assertEquals(BigInteger("100000000000000000000"), bob_tokens_1)
        // Burnt a lot of gas!
        assertEquals(BigInteger("5000000"), (bob_balance_before - bob_balance_1)/BigInteger("50000000000"))
        while (block.header.timestamp < withdraw_time) {
            block = blockchain.createBlock()
        }
        var withdrawResult2 = a_withdraw.callFunction("withdraw")
        assertTrue(withdrawResult2.isSuccessful)
        val bob_balance_2 = blockchain.blockchain.repository.getBalance(bob.address)
        val bob_tokens_2 = a_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger
        assertEquals(BigInteger.ZERO, bob_tokens_2)
        // 10 ETH minus gas cost
        assertEquals(BigInteger("99998301200000000000"), bob_balance_2 - bob_balance_1)
    }

    @Test
    fun `withdraw not approved`() {
         blockchain.sender = alice
        val a_token = blockchain.submitNewContract(token)
        val mintResult = a_token.callFunction("mint", bob.address, BigInteger("100000000000000000000"))
        assertTrue(mintResult.isSuccessful)
        var block = blockchain.createBlock();
        // Withdraw only in 200 seconds
        val withdraw_time = block.header.timestamp
        val a_withdraw = blockchain.submitNewContract(excessWithdraw, withdraw_time, a_token.address)
        blockchain.sendEther(a_withdraw.address, BigInteger("100000000000000000000"))
        assertEquals(BigInteger("100000000000000000000"), blockchain.blockchain.repository.getBalance(a_withdraw.address))
        blockchain.sender = bob     
        var withdrawResult1 = a_withdraw.callFunction("withdraw")
        assertFalse(withdrawResult1.isSuccessful)
        // Approved by not enough
        val approveResult2 = a_token.callFunction("approve", a_withdraw.address, BigInteger("50000000000000000000"))
        assertTrue(approveResult2.isSuccessful)
        var withdrawResult2 = a_withdraw.callFunction("withdraw")
        assertFalse(withdrawResult2.isSuccessful)
        // Approved enough
        val approveResult3 = a_token.callFunction("approve", a_withdraw.address, BigInteger("100000000000000000000"))
        assertTrue(approveResult3.isSuccessful)
        var withdrawResult3 = a_withdraw.callFunction("withdraw")
        assertTrue(withdrawResult3.isSuccessful)
    }

    @Test
    fun `escape not payable`() {
        assertTrue(contribute(bob, 1000000L, 1))
        blockchain.sender = bob
        var escapeResult1 = dist.callFunction(10000000L, "escape", 1)
        assertFalse(escapeResult1.isSuccessful)
        var escapeResult2 = dist.callFunction("escape", 1)
        assertTrue(escapeResult2.isSuccessful)
    }
}

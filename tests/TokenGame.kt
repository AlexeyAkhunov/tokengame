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
    lateinit var excess_token: SolidityContract
    lateinit var excess_withdraw: SolidityContract
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

    @Test
    fun `game token creation`() {
        blockchain.sender = bob
        val result = dist.callFunction(1000000L, "contribute", 0)
        assertTrue(result.isSuccessful)
        assertEquals(BigInteger("1000000"), dist.callConstFunction("total_wei_given")[0] as java.math.BigInteger)
        assertEquals(BigInteger("1000000"), blockchain.blockchain.repository.getBalance(dist.address))
    }

    @Test
    fun `strangers cannot mint`() {
        blockchain.sender = bob
        val mintResult1 = dist_token.callFunction("mint", bob.address, BigInteger.ONE)
        assertFalse(mintResult1.isSuccessful)
    }
}

pragma solidity ^0.4.10;

contract Token {
    uint256 public totalSupply;

    /* This creates an array with all balances */
    mapping (address => uint256) public balanceOf;
    mapping (address => mapping (address => uint256)) public allowance;

    /* This generates a public event on the blockchain that will notify clients */
    event Transfer(address indexed from, address indexed to, uint256 value);

    /* Send tokens */
    function transfer(address _to, uint256 _value) {
        require(balanceOf[msg.sender] >= _value);            // Check if the sender has enough
        require(balanceOf[_to] + _value >= balanceOf[_to]);  // Check for overflows
        balanceOf[msg.sender] -= _value;                     // Subtract from the sender
        balanceOf[_to] += _value;                            // Add the same to the recipient
        Transfer(msg.sender, _to, _value);                   // Notify anyone listening that this transfer took place
    }

    /* Allow another contract to spend some tokens in your behalf */
    function approve(address _spender, uint256 _value) returns (bool success) {
        allowance[msg.sender][_spender] = _value;
        return true;
    }

    /* A contract attempts to get the tokens */
    function transferFrom(address _from, address _to, uint256 _value) returns (bool success) {
        require(balanceOf[_from] >= _value);                 // Check if the sender has enough
        require(balanceOf[_to] + _value >= balanceOf[_to]);  // Check for overflows
        require(_value <= allowance[_from][msg.sender]);     // Check allowance
        balanceOf[_from] -= _value;                          // Subtract from the sender
        balanceOf[_to] += _value;                            // Add the same to the recipient
        allowance[_from][msg.sender] -= _value;
        Transfer(_from, _to, _value);
        return true;
    }

    uint public id; /* To ensure distinct contracts for different tokens owned by the same owner */
    address public owner;

    function Token(uint _id) {
        owner = msg.sender;
        id = _id;
    }

    /* Allows the owner to mint more tokens */
    function mint(address _to, uint256 _value) returns (bool) {
        require(msg.sender == owner);                        // Only the owner is allowed to mint
        require(balanceOf[_to] + _value >= balanceOf[_to]);  // Check for overflows
        balanceOf[_to] += _value;
        totalSupply += _value;
        return true;
    }
}

// Withdraw contracts with 1 token giving entitlement to 1 wei
contract ExcessWithdraw {
    Token public token;
    uint public release_time;

    function ExcessWithdraw(uint _release_time, Token _token) {
        release_time = _release_time;
        token = _token;
    }

    function () payable {}

    function withdraw() {
        require(now >= release_time);
        require(token.balanceOf(msg.sender) > 0);
        uint amount = token.balanceOf(msg.sender);
        if (!token.transferFrom(msg.sender, this, amount) || !msg.sender.send(amount)) {
            throw;
        }
    }
}

contract TokenDistribution {
    address public owner;
    uint public target_in_wei;                                 /* Minimum amount to collect - otherwise return everything */
    uint public cap_in_wei;                                    /* Maximum amount to accept - return the rest */
    uint public tokens_to_mint;                                /* How many tokens need to be issued */
    uint constant INITIAL_DURATION = 1 weeks;
    uint constant TIME_EXTENSION_FROM_DOUBLING = 1 weeks;
    uint constant TIME_OF_HALF_DECAY = 1 days;
    uint constant MAX_LOCK_WEEKS = 100;                        /* Maximum number of weeks that the excess contribution can be locked for */
    uint constant FIXED_POINT_ONE = 1000000000000;             /* Equivalent of number "1" for fixed point arithmetics */
    uint constant FIXED_POINT_PRC = 1070000000000;             /* Equivalent of number "1.07" for fixed point arithmetics */
    Token public token;                                        /* Token contract where sold tokens are minted */
    uint public end_time;                                      /* Current end time */
    uint last_time = 0;                                        /* Timestamp of the latest contribution */
    uint256 public ema = 0;                                    /* Current value of the EMA */
    uint public total_wei_given = 0;                           /* Total amount of wei given via fallback function */
    uint public total_wei_accepted = 0;                        /* Total amount of wei accepted */
    mapping (uint => Token) public excess_tokens;              /* Excess tokens organised by lock weeks */
    mapping (uint => ExcessWithdraw) public excess_withdraws;  /* Excess withdraw contracts organised by lock weeks */
    mapping (uint => uint) public wei_given_to_bucket;         /* Amount of wei given to specific bucket (lock_weeks is key in the mapping) */
    mapping (uint => uint) public wei_accepted_from_bucket;    /* Amount of wei accepted from specific bucket (lock_weeks is the key in the mapping) */
    mapping (address => mapping (uint => uint)) public contributions; /* Contributions of a participant (first key) to a bucket (second key) */
    uint public last_bucket_closed = MAX_LOCK_WEEKS + 1;       /* Counter (goes from max_lock_weeks to 0) used to finalise bucket by bucket */
    uint public cap_remainder;                                 /* As the buckets are getting closed, the cap_remainder reduced to what is left to allocate */

    // sqrt(2), sqrt(sqrt(2)), sqrt(sqrt(sqrt(2))), ...
    uint[] FIXED_POINT_DECAYS =
        [1414213562370, 1189207115000, 1090507732670, 1044273782430, 1021897148650, 1010889286050, 1005429901110, 1002711275050, 1002711275050, 1000677130690,
         1000338508050, 1000169239710, 1000084616270, 1000042307240, 1000021153400, 1000010576640, 1000005288310, 1000002644150, 1000001322070, 1000000661040];

    function TokenDistribution(uint _target_in_wei, uint _cap_in_wei, uint _tokens_to_mint) {
        owner = msg.sender;
        target_in_wei = _target_in_wei;
        cap_in_wei = _cap_in_wei;
        cap_remainder = _cap_in_wei;
        tokens_to_mint = _tokens_to_mint;
        token = new Token(MAX_LOCK_WEEKS + 1);
        end_time = now + INITIAL_DURATION;
    }

    function exponential_decay(uint value, uint time) private returns (uint decayed) {
        if (time == 0) {
            return value;
        }
        // First, we half the value for each unit of TIME_OF_HALF_DECAY
        uint v = value / (1 << (time / TIME_OF_HALF_DECAY));
        uint t = time % TIME_OF_HALF_DECAY;
        uint decay = TIME_OF_HALF_DECAY >> 1; // This is half of the time of half decay
        for(uint8 i = 0; i<20 && decay > 0; ++i) {
            if (t >= decay) {
                v = v * FIXED_POINT_ONE / FIXED_POINT_DECAYS[i];
                t -= decay;
            }
            decay >>= 1;
        }
        return v;
    }

    function contribute(uint lock_weeks) payable {
        require(now <= end_time);   // Check that the sale has not ended
        require(msg.value > 0);     // Check that something has been sent
        require(lock_weeks <= MAX_LOCK_WEEKS);
        contributions[msg.sender][lock_weeks] += msg.value;
        wei_given_to_bucket[lock_weeks] += msg.value;
        total_wei_given += msg.value;
        ema = msg.value + exponential_decay(ema, now - last_time);
        last_time = now;
        uint extended_time = now + ema * TIME_EXTENSION_FROM_DOUBLING / total_wei_given;
        if (extended_time > end_time) {
            end_time = extended_time;
        }
    }

    function close_next_bucket() {
        require(now > end_time);         /* Can only close buckets after the end of sale */
        require(last_bucket_closed > 0); /* Not all buckets closed yet */
        uint bucket = last_bucket_closed - 1;
        while (bucket > 0 && wei_given_to_bucket[bucket] == 0) {
            bucket--;
        }
        uint bucket_contribution = wei_given_to_bucket[bucket];
        if (bucket_contribution > 0) {
            // Current bucket will get the biggest contritubion multiplier (due to highest lock time)
            // The muliplier decays by 1.07 as the lock time decreased by a week
            uint contribution_multiplier = FIXED_POINT_ONE;
            uint contribution_sum = bucket_contribution;
            uint b = bucket;
            while (b > 0) {
                b--;
                contribution_multiplier = contribution_multiplier * FIXED_POINT_ONE / FIXED_POINT_PRC;
                contribution_sum += wei_given_to_bucket[b] * contribution_multiplier / FIXED_POINT_ONE;
            }
            // Compute accepted contribution for this bucket
            uint accepted = cap_remainder * wei_given_to_bucket[bucket] / contribution_sum;
            if (accepted > bucket_contribution) {
                accepted = bucket_contribution;
            }
            wei_accepted_from_bucket[bucket] = accepted;
            total_wei_accepted += accepted;
            cap_remainder -= accepted;
            if (accepted < bucket_contribution) {
                // Only call if there is an excess
                move_excess_for_bucket(bucket, bucket_contribution - accepted);
            }
        }
        last_bucket_closed = bucket;
    }

    function move_excess_for_bucket(uint bucket, uint excess) private {
        Token token_contract = new Token(bucket);
        excess_tokens[bucket] = token_contract;
        ExcessWithdraw withdraw_contract = new ExcessWithdraw(end_time + bucket * (1 weeks), token_contract);
        excess_withdraws[bucket] = withdraw_contract;
        require(withdraw_contract.send(excess));
    }

    // Claim tokens for players and send ether to the owner
    function claim_tokens(address player, uint bucket) {
        require(last_bucket_closed == 0); /* Claims only allowed when all buckets are closed */
        uint contribution = contributions[player][bucket];
        if (contribution > 0) {
            contributions[player][bucket] = 0;
            if (total_wei_given < target_in_wei) {
                // Did not reach the target, return everything
                require(player.send(contribution));
            } else {
                uint wei_accepted = contribution * wei_accepted_from_bucket[bucket] / wei_given_to_bucket[bucket];
                uint tokens = wei_accepted * tokens_to_mint / total_wei_accepted;
                require(tokens == 0 || token.mint(player, tokens));
                Token excess_token = excess_tokens[bucket];
                uint excess = contribution - wei_accepted;
                require(excess == 0 || excess_token.mint(player, excess));
                require(wei_accepted == 0 || owner.send(wei_accepted));
            }
        }
    }
}

contract PrizePot {
    TokenDistribution public dist;

    function PrizePot(TokenDistribution _dist) {
        dist = _dist;
    }

    function() payable {}

    function claim_prize() {
        require(dist.total_wei_given() >= dist.target_in_wei());
        Token token = dist.token();
        uint token_amount = token.balanceOf(msg.sender);
        uint wei_amount = this.balance * token_amount / (dist.tokens_to_mint() - token.balanceOf(this));
        if (!token.transferFrom(msg.sender, this, token_amount) || !msg.sender.send(wei_amount)) {
            throw;
        }
    }

    function cancel() {
        require(dist.last_bucket_closed() == 0);
        require(dist.total_wei_given() < dist.target_in_wei());
        require(dist.owner().send(this.balance));
    } 
}

contract TokenGame is TokenDistribution {

    PrizePot public prize_pot;

    function TokenGame() TokenDistribution(0, 0, 1000) {
        prize_pot = new PrizePot(this);
    }
}

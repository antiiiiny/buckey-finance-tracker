package com.buckey.financetracker.data.parser

import java.time.Instant

/**
 * Interface for bank-specific SMS parsers.
 *
 * Each bank gets its own implementation in [banks/].
 * A parser takes a raw SMS body and returns a [ParsedTransaction]
 * if the message matches the bank's format, or null if it can't be parsed.
 */
interface TransactionParser {

    /**
     * Attempts to parse a raw SMS body into a [ParsedTransaction].
     *
     * @param senderAddress The sender ID (e.g. "AX-SIBSMS-S")
     * @param rawBody The full SMS body text
     * @param receivedAt When the SMS arrived. Only formats that carry no date of
     *   their own use it (SIB's ATM alert); without it they return null.
     * @return A [ParsedTransaction] if parsing succeeds, null otherwise
     */
    fun parse(senderAddress: String, rawBody: String, receivedAt: Instant? = null): ParsedTransaction?
}
from fin.hsbc_labels import hsbc_savings_merchant


def test_salary_keeps_the_employer_not_the_ref():
    assert hsbc_savings_merchant(
        "QUBE R & T HK LTD SALARY 24AUG ZHOU Y******", 7481250
    ) == "Salary — QUBE R & T"


def test_fps_and_card_pans_name_the_other_account():
    assert hsbc_savings_merchant(
        "ZHOU YIXIANG HC12682046299064 20AUG 8383830019792217 -", 758269
    ) == "Mox"
    assert hsbc_savings_merchant(
        "GOLD/EXCHANGE DEBIT 6250-9800-1702-0071", -42006
    ) == "Pulse Dual Currency"
    assert hsbc_savings_merchant(
        "N82079148809(20AUG26) 4366-0502-2086-3315", -46841
    ) == "EveryMile"


def test_american_express_and_payme_beat_a_leaked_pan():
    assert hsbc_savings_merchant(
        "AMERICAN EXPRESS CARD 4366-0502-2086-3315", -4621985
    ) == "AMEX"
    assert hsbc_savings_merchant(
        "HC12662456651333 24JUN FROM PAYME(HSBC)162", -2718648
    ) == "PayMe"
    assert hsbc_savings_merchant("WITHDRAWAL DEPOSIT", -161600) == "CNY withdrawal"
    assert hsbc_savings_merchant("DEPOSIT HC12682664429444 26AUG", 211259) == "CNY deposit"
    assert hsbc_savings_merchant(
        "HC12691428462251 14SEP CITIBANK EUROPE PLC", 199998
    ) == "Citibank Europe"
    assert hsbc_savings_merchant("HC12691428462251 14SEP", 199998) == "HSBC transfer"

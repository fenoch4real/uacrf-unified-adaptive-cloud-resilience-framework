"""Finite design examples only; not connector, authorization or production scheduling code."""
from decimal import Decimal
from fractions import Fraction
from datetime import datetime

def classify_import(base, local, source, *, scope_complete=True, valid=True, explicit_withdrawal=False):
    if not scope_complete or not valid:
        return 'INVALID'
    if explicit_withdrawal:
        return 'WITHDRAWN'
    if source == local:
        return 'UNCHANGED'
    if base is not None and source == base:
        return 'UNCHANGED'  # Existing local override is retained; source did not change.
    if local is None or (base is not None and local == base):
        return 'NEW'
    return 'CONFLICT'

def normalize_target(value, source_unit, target_unit, source_window, target_window, *, allow_fraction=False):
    if source_window != target_window:
        raise ValueError('Measurement windows require an explicit compatible mapping')
    number=Decimal(str(value))
    if not number.is_finite() or number<0:
        raise ValueError('Invalid source target')
    units={('seconds','minutes'):Fraction(1,60),('minutes','seconds'):Fraction(60,1)}
    if source_unit==target_unit:
        converted=Fraction(number)
    elif (source_unit,target_unit)==('fraction','percent') and allow_fraction:
        if number>1:raise ValueError('Fraction exceeds one')
        converted=Fraction(number)*100
    elif (source_unit,target_unit) in units:
        converted=Fraction(number)*units[(source_unit,target_unit)]
    else:
        raise ValueError('Unapproved unit mapping')
    denominator=converted.denominator
    for prime in [2,5]:
        while denominator%prime==0:denominator//=prime
    if denominator!=1:
        raise ValueError('A nonterminating result needs an explicit approved rounding policy')
    result=Decimal(converted.numerator)/Decimal(converted.denominator)
    if target_unit=='percent' and result>100:raise ValueError('Percent exceeds one hundred')
    return result

def duplicate_disposition(previous_digest, incoming_digest):
    return 'NEW' if previous_digest is None else ('REPLAY' if previous_digest==incoming_digest else 'CONFLICT')

def freshness(observed_at, received_at, checked_at, now, maximum_age_seconds, *, basis='OBSERVED_AT'):
    """Conservative example: ingestion time cannot renew an old observation."""
    stamp=checked_at if basis=='SOURCE_CHECKED_AT' else observed_at
    if stamp is None:return 'UNKNOWN'
    if stamp.tzinfo is None or now.tzinfo is None:raise ValueError('Timezone required')
    age=(now-stamp).total_seconds()
    return 'UNKNOWN' if age<0 or age>maximum_age_seconds else 'WITHIN_AGE_LIMIT_NOT_QUALIFIED'
